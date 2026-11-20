package com.hamiltel.gmlc;

import com.fasterxml.jackson.databind.JsonNode;
import com.hamiltel.gmlc.support.EmbeddedPostgresSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.util.DefaultUriBuilderFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The governance layer: who may call, from where, how often, and what the
 * gateway records when it says no. Every refusal is audited; no audit row and
 * no response carries the key or a position.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ClientGovernanceTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        EmbeddedPostgresSupport.register(registry);
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RestTemplateBuilder restTemplateBuilder;

    @LocalServerPort
    private int port;

    /**
     * HttpURLConnection treats a 401 on a streamed POST as an authentication
     * challenge it must replay, and fails instead of returning the response.
     * The JDK HttpClient returns the 401 the filter actually wrote.
     */
    private TestRestTemplate bufferedRest() {
        TestRestTemplate client = new TestRestTemplate(
                restTemplateBuilder.requestFactory(settings -> new JdkClientHttpRequestFactory()));
        client.setUriTemplateHandler(new DefaultUriBuilderFactory("http://localhost:" + port));
        return client;
    }

    @Test
    void aRegisteredKeyIdentifiesItsClient() {
        JsonNode registered = EmbeddedPostgresSupport.registerClient(rest, "identity");
        ResponseEntity<JsonNode> response = EmbeddedPostgresSupport.get(rest, "/v1/clients/me",
                registered.path("apiKey").asText());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(body.path("clientId").asText()).isEqualTo(registered.path("clientId").asText());
        assertThat(body.path("tenantId").asLong()).isEqualTo(1);
        assertThat(body.path("purposes").toString()).contains("TEST");
        assertThat(body.toString()).doesNotContain("apiKey").doesNotContain("keyHash");
    }

    @Test
    void theApiKeyHeaderIsAcceptedAsWellAsBearer() {
        JsonNode registered = EmbeddedPostgresSupport.registerClient(rest, "xkey");
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", registered.path("apiKey").asText());

        ResponseEntity<JsonNode> response = rest.exchange("/v1/clients/me", HttpMethod.GET,
                new HttpEntity<>(headers), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().path("name").asText())
                .isEqualTo(registered.path("name").asText());
    }

    @Test
    void aMissingKeyIsRefusedAndAudited() {
        Instant since = Instant.now();

        ResponseEntity<JsonNode> response = rest.getForEntity("/v1/clients/me", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().path("status").asInt()).isEqualTo(401);
        assertThat(auditCountSince(since, "AUTH_DENIED")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select detail->>'reason' from audit_event"
                + " where action = 'AUTH_DENIED' order by audit_id desc limit 1", String.class))
                .isEqualTo("MISSING_KEY");
    }

    @Test
    void anUnknownKeyIsRefusedAndAudited() {
        Instant since = Instant.now();

        ResponseEntity<JsonNode> response = EmbeddedPostgresSupport.get(rest, "/v1/clients/me",
                "gk_" + "A".repeat(43));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(auditCountSince(since, "AUTH_DENIED")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select detail->>'reason' from audit_event"
                + " where action = 'AUTH_DENIED' order by audit_id desc limit 1", String.class))
                .isEqualTo("UNKNOWN_KEY");
    }

    @Test
    void anAddressOutsideTheAllowlistIsForbidden() {
        JsonNode registered = EmbeddedPostgresSupport.registerClient(rest, "pinned",
                null, null, List.of("10.0.0.0/8"));
        Instant since = Instant.now();

        ResponseEntity<JsonNode> response = EmbeddedPostgresSupport.get(rest, "/v1/clients/me",
                registered.path("apiKey").asText());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().path("detail").asText()).contains("address");
        assertThat(auditCountSince(since, "AUTH_DENIED")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select detail->>'reason' from audit_event"
                + " where action = 'AUTH_DENIED' order by audit_id desc limit 1", String.class))
                .isEqualTo("ADDRESS_NOT_ALLOWED");
    }

    @Test
    void thePerSecondQuotaStopsBurstsAndSaysWhenToRetry() {
        JsonNode registered = EmbeddedPostgresSupport.registerClient(rest, "burst",
                1, 1_000_000, null);
        String key = registered.path("apiKey").asText();
        Instant since = Instant.now();

        ResponseEntity<JsonNode> throttled = null;
        for (int attempt = 0; attempt < 10 && throttled == null; attempt++) {
            EmbeddedPostgresSupport.get(rest, "/v1/clients/me", key);
            ResponseEntity<JsonNode> next = EmbeddedPostgresSupport.get(rest, "/v1/clients/me", key);
            if (next.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                throttled = next;
            }
        }

        assertThat(throttled).as("a one-per-second client cannot make two calls in one second")
                .isNotNull();
        assertThat(throttled.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
        assertThat(throttled.getBody().path("detail").asText()).contains("allowance");
        assertThat(auditCountSince(since, "QUOTA_EXCEEDED")).isEqualTo(1);
    }

    @Test
    void registrationRefusesALawfulInterceptionPurpose() {
        Map<String, Object> body = Map.of("name", "tapped", "purposes", List.of("LAWFUL_INTERCEPTION"));

        ResponseEntity<JsonNode> response = rest.exchange("/v1/admin/clients", HttpMethod.POST,
                new HttpEntity<>(body, EmbeddedPostgresSupport.adminHeaders()), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().path("title").asText()).isEqualTo("Invalid purposes");
        assertThat(response.getBody().path("rejected").toString()).contains("LAWFUL_INTERCEPTION");
        assertThat(response.getBody().path("allowed").toString()).contains("EMERGENCY");
    }

    @Test
    void registrationRefusesAHostnameAllowlist() {
        Map<String, Object> body = Map.of(
                "name", "hostname-rule",
                "purposes", List.of("TEST"),
                "ipAllowlist", List.of("example.com"));

        ResponseEntity<JsonNode> response = rest.exchange("/v1/admin/clients", HttpMethod.POST,
                new HttpEntity<>(body, EmbeddedPostgresSupport.adminHeaders()), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().path("fields").has("ipAllowlist[0]")).isTrue();
    }

    @Test
    void registrationRefusesADuplicateName() {
        JsonNode first = EmbeddedPostgresSupport.registerClient(rest, "twice");

        ResponseEntity<JsonNode> second = rest.exchange("/v1/admin/clients", HttpMethod.POST,
                new HttpEntity<>(Map.of("name", first.path("name").asText(), "purposes", List.of("TEST")),
                        EmbeddedPostgresSupport.adminHeaders()), JsonNode.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void registrationWithoutTheAdminKeyIsRefused() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<JsonNode> response = bufferedRest().exchange("/v1/admin/clients", HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "no-admin", "purposes", List.of("TEST")), headers),
                JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().path("title").asText()).isEqualTo("Unauthorised");
    }

    @Test
    void aDailyQuotaRefusalPointsAtMidnight() {
        JsonNode registered = EmbeddedPostgresSupport.registerClient(rest, "daily",
                1000, 1, null);
        String key = registered.path("apiKey").asText();

        EmbeddedPostgresSupport.get(rest, "/v1/clients/me", key);
        ResponseEntity<JsonNode> second = EmbeddedPostgresSupport.get(rest, "/v1/clients/me", key);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(Long.parseLong(second.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)))
                .isBetween(1L, 86_400L);
        assertThat(second.getBody().path("title").asText()).isEqualTo("Quota exceeded");
        assertThat(second.getBody().path("detail").asText()).contains("rate_per_day");
    }

    private int auditCountSince(Instant since, String action) {
        Integer count = jdbc.queryForObject(
                "select count(*) from audit_event where action = ? and occurred_at >= ?",
                Integer.class, action, java.sql.Timestamp.from(since));
        return count == null ? 0 : count;
    }
}
