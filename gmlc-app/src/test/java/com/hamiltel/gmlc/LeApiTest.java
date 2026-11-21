package com.hamiltel.gmlc;

import com.fasterxml.jackson.databind.JsonNode;
import com.hamiltel.gmlc.location.LocationRequestRepository;
import com.hamiltel.gmlc.location.RequestState;
import com.hamiltel.gmlc.support.EmbeddedPostgresSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.util.DefaultUriBuilderFactory;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Le request path end to end: consent is required, the subject is never
 * echoed, an accepted request is recorded with its state, and a request that
 * cannot be located fails with a stable code and a CDR instead of a position.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LeApiTest {

    private static final String TENANT_IMSI = "001010000000001";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        EmbeddedPostgresSupport.register(registry);
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private RestTemplateBuilder restTemplateBuilder;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private LocationRequestRepository requests;

    @LocalServerPort
    private int port;

    private TestRestTemplate http() {
        TestRestTemplate client = new TestRestTemplate(
                restTemplateBuilder.requestFactory(settings -> new JdkClientHttpRequestFactory()));
        client.setUriTemplateHandler(new DefaultUriBuilderFactory("http://localhost:" + port));
        return client;
    }

    private ResponseEntity<JsonNode> post(String path, Object body, String apiKey) {
        HttpHeaders headers = EmbeddedPostgresSupport.bearer(apiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http().exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private String newClientKey(String prefix) {
        return EmbeddedPostgresSupport.registerClient(rest, prefix, 1000, 100_000, null)
                .path("apiKey").asText();
    }

    private static String uniqueMsisdn() {
        return "2567" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
    }

    private JsonNode grantConsent(String apiKey, Map<String, Object> body) {
        ResponseEntity<JsonNode> response = post("/v1/consents", body, apiKey);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    @Test
    void aRequestWithoutConsentIsForbiddenAndAudited() {
        String key = newClientKey("le-denied");
        String msisdn = uniqueMsisdn();
        Instant since = Instant.now();

        ResponseEntity<JsonNode> response = post("/v1/locations",
                Map.of("msisdn", msisdn, "purpose", "TEST"), key);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        JsonNode problem = response.getBody();
        assertThat(problem.path("title").asText()).isEqualTo("Privacy not satisfied");
        assertThat(problem.path("code").asText()).isEqualTo("PRIVACY_NOT_SATISFIED");
        assertThat(problem.path("purpose").asText()).isEqualTo("TEST");
        assertThat(problem.toString()).doesNotContain(msisdn);

        assertThat(jdbc.queryForObject("select count(*) from audit_event"
                + " where action = 'PRIVACY_DENIED' and outcome = 'DENIED' and occurred_at >= ?",
                Integer.class, java.sql.Timestamp.from(since))).isEqualTo(1);
        assertThat(jdbc.queryForObject("select detail->>'basis' from audit_event"
                + " where action = 'PRIVACY_DENIED' order by audit_id desc limit 1", String.class))
                .isEqualTo("NO_CONSENT");
        assertThat(jdbc.queryForObject("select count(*) from location_request"
                + " where subscriber_msisdn = ?", Integer.class, msisdn)).isZero();
    }

    @Test
    void withConsentTheRequestIsRecordedAndFailsClosed() {
        String key = newClientKey("le-granted");
        String msisdn = uniqueMsisdn();
        JsonNode consent = grantConsent(key,
                Map.of("msisdn", msisdn, "purpose", "TEST", "channel", "WEB"));
        assertThat(consent.path("subjectRef").asText()).hasSize(64);

        ResponseEntity<JsonNode> created = post("/v1/locations",
                Map.of("msisdn", msisdn, "purpose", "TEST"), key);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = created.getBody();
        assertThat(body.path("state").asText()).isEqualTo("FAILED");
        assertThat(body.path("errorCode").asText()).isEqualTo("NO_LCS_SOURCE");
        assertThat(body.path("purpose").asText()).isEqualTo("TEST");
        assertThat(body.path("priority").asText()).isEqualTo("NORMAL");
        assertThat(body.path("replayed").asBoolean()).isFalse();
        assertThat(body.toString()).doesNotContain(msisdn).doesNotContain("subscriber");
        assertThat(body.path("errorDetail").asText()).contains("Diameter");

        UUID requestId = UUID.fromString(body.path("requestId").asText());
        assertThat(created.getHeaders().getFirst(HttpHeaders.LOCATION))
                .isEqualTo("/v1/locations/" + requestId);

        ResponseEntity<JsonNode> fetched = EmbeddedPostgresSupport.get(rest,
                "/v1/locations/" + requestId, key);
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody().path("requestId").asText()).isEqualTo(requestId.toString());
        assertThat(fetched.getBody().path("state").asText()).isEqualTo("FAILED");
        assertThat(fetched.getBody().has("replayed")).isFalse();

        assertThat(jdbc.queryForObject("select state from location_request where request_id = ?",
                String.class, requestId)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select error_code from location_request where request_id = ?",
                String.class, requestId)).isEqualTo("NO_LCS_SOURCE");

        assertThat(jdbc.queryForObject("select result_code from cdr where request_id = ?",
                String.class, requestId)).isEqualTo("NO_LCS_SOURCE");
        assertThat(jdbc.queryForObject("select record_status from cdr where request_id = ?",
                String.class, requestId)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select consent_id from cdr where request_id = ?",
                UUID.class, requestId))
                .isEqualTo(UUID.fromString(consent.path("consentId").asText()));
        String subscriberRef = jdbc.queryForObject(
                "select subscriber_ref from cdr where request_id = ?", String.class, requestId);
        assertThat(subscriberRef).hasSize(64).doesNotContain(msisdn);

        assertThat(jdbc.queryForObject("select count(*) from audit_event"
                + " where action = 'REQUEST_ACCEPTED' and request_id = ?", Integer.class, requestId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select detail->>'code' from audit_event"
                + " where action = 'REQUEST_FAILED' and request_id = ?", String.class, requestId))
                .isEqualTo("NO_LCS_SOURCE");
        assertThat(jdbc.queryForObject("select subject_ref from audit_event"
                        + " where action = 'REQUEST_ACCEPTED' and request_id = ?",
                String.class, requestId))
                .isEqualTo(subscriberRef);
    }

    @Test
    void aFailedRequestCannotBeAdvancedAgain() {
        String key = newClientKey("le-terminal");
        String msisdn = uniqueMsisdn();
        grantConsent(key, Map.of("msisdn", msisdn, "purpose", "TEST"));
        UUID requestId = UUID.fromString(post("/v1/locations",
                Map.of("msisdn", msisdn, "purpose", "TEST"), key)
                .getBody().path("requestId").asText());

        assertThatThrownBy(() -> requests.advance(1L, requestId,
                RequestState.REQUESTED, RequestState.ANSWERED, null, null))
                .isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForObject("select state from location_request where request_id = ?",
                String.class, requestId)).isEqualTo("FAILED");
    }

    @Test
    void anIdempotencyKeyReplaysTheSameRequest() {
        String key = newClientKey("le-idem");
        String idempotencyKey = "idem-" + UUID.randomUUID();
        grantConsent(key, Map.of("imsi", TENANT_IMSI, "purpose", "TEST"));

        ResponseEntity<JsonNode> first = http().exchange("/v1/locations", HttpMethod.POST,
                new HttpEntity<>(Map.of("imsi", TENANT_IMSI, "purpose", "TEST"),
                        headersWithIdempotency(key, idempotencyKey)), JsonNode.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = first.getBody();
        assertThat(body.path("state").asText()).isEqualTo("FAILED");

        ResponseEntity<JsonNode> second = http().exchange("/v1/locations", HttpMethod.POST,
                new HttpEntity<>(Map.of("imsi", TENANT_IMSI, "purpose", "TEST"),
                        headersWithIdempotency(key, idempotencyKey)), JsonNode.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody().path("requestId").asText())
                .isEqualTo(body.path("requestId").asText());
        assertThat(second.getBody().path("replayed").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from location_request"
                + " where idempotency_key = ?", Integer.class, idempotencyKey)).isEqualTo(1);
    }

    @Test
    void anOverlongIdempotencyHeaderIsRejected() {
        String key = newClientKey("le-longidem");

        ResponseEntity<JsonNode> response = http().exchange("/v1/locations", HttpMethod.POST,
                new HttpEntity<>(Map.of("msisdn", uniqueMsisdn(), "purpose", "TEST"),
                        headersWithIdempotency(key, "k".repeat(81))), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().path("code").asText()).isEqualTo("IDEMPOTENCY_KEY_TOO_LONG");
    }

    @Test
    void aSubjectIsRequiredAndItsImsiMustBeInTheTenantPlmn() {
        String key = newClientKey("le-subject");

        ResponseEntity<JsonNode> missing = post("/v1/locations", Map.of("purpose", "TEST"), key);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(missing.getBody().path("code").asText()).isEqualTo("SUBJECT_REQUIRED");

        ResponseEntity<JsonNode> foreign = post("/v1/locations",
                Map.of("msisdn", uniqueMsisdn(), "imsi", "999700000000001", "purpose", "TEST"), key);
        assertThat(foreign.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(foreign.getBody().path("code").asText()).isEqualTo("IMSI_NOT_IN_TENANT_PLMN");
    }

    @Test
    void accuracyBeyondTheEntitlementIsRefused() {
        String key = newClientKey("le-accuracy");

        ResponseEntity<JsonNode> response = post("/v1/locations",
                Map.of("msisdn", uniqueMsisdn(), "purpose", "TEST", "accuracyMeters", 10), key);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().path("code").asText()).isEqualTo("ACCURACY_NOT_ALLOWED");
        assertThat(response.getBody().path("detail").asText()).contains("5000");
    }

    @Test
    void aPurposeTheClientDoesNotHoldIsRefused() {
        String key = newClientKey("le-purpose");

        ResponseEntity<JsonNode> response = post("/v1/locations",
                Map.of("msisdn", uniqueMsisdn(), "purpose", "SAFETY"), key);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().path("code").asText()).isEqualTo("PURPOSE_NOT_ALLOWED");
    }

    @Test
    void aMalformedFieldIsReportedByName() {
        String key = newClientKey("le-fields");

        ResponseEntity<JsonNode> response = post("/v1/locations",
                Map.of("msisdn", "12", "purpose", "TEST"), key);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().path("fields").has("msisdn")).isTrue();
    }

    @Test
    void anotherClientCannotReadTheRequest() {
        String owner = newClientKey("le-owner");
        String other = newClientKey("le-other");
        String msisdn = uniqueMsisdn();
        grantConsent(owner, Map.of("msisdn", msisdn, "purpose", "TEST"));
        UUID requestId = UUID.fromString(post("/v1/locations",
                Map.of("msisdn", msisdn, "purpose", "TEST"), owner)
                .getBody().path("requestId").asText());

        ResponseEntity<JsonNode> response = EmbeddedPostgresSupport.get(rest,
                "/v1/locations/" + requestId, other);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().path("requestId").asText()).isEqualTo(requestId.toString());
    }

    @Test
    void anUnknownRequestIsNotFound() {
        String key = newClientKey("le-unknown");
        UUID unknown = UUID.randomUUID();

        ResponseEntity<JsonNode> response = EmbeddedPostgresSupport.get(rest,
                "/v1/locations/" + unknown, key);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().path("consentId").isMissingNode()).isTrue();
        assertThat(response.getBody().toString()).doesNotContain("SQL");
    }

    private static HttpHeaders headersWithIdempotency(String apiKey, String idempotencyKey) {
        HttpHeaders headers = EmbeddedPostgresSupport.bearer(apiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", idempotencyKey);
        return headers;
    }
}
