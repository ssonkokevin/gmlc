package com.hamiltel.gmlc;

import com.fasterxml.jackson.databind.JsonNode;
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

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Consent is the record the gateway relies on, so it is created, read and
 * revoked as a resource of its own. It names the subscriber by pseudonym and
 * never returns the MSISDN or IMSI that produced it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConsentApiTest {

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

    private ResponseEntity<JsonNode> get(String path, String apiKey) {
        return http().exchange(path, HttpMethod.GET,
                new HttpEntity<>(EmbeddedPostgresSupport.bearer(apiKey)), JsonNode.class);
    }

    private ResponseEntity<JsonNode> delete(String path, String apiKey) {
        return http().exchange(path, HttpMethod.DELETE,
                new HttpEntity<>(EmbeddedPostgresSupport.bearer(apiKey)), JsonNode.class);
    }

    private String newClientKey(String prefix) {
        return EmbeddedPostgresSupport.registerClient(rest, prefix, 1000, 100_000, null)
                .path("apiKey").asText();
    }

    private static String uniqueMsisdn() {
        return "2567" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
    }

    @Test
    void aGrantIsCreatedReadAndRevoked() {
        String key = newClientKey("consent-life");
        String msisdn = uniqueMsisdn();

        ResponseEntity<JsonNode> created = post("/v1/consents",
                Map.of("msisdn", msisdn, "purpose", "TEST", "channel", "WEB"), key);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = created.getBody();
        UUID consentId = UUID.fromString(body.path("consentId").asText());
        assertThat(body.path("status").asText()).isEqualTo("GRANTED");
        assertThat(body.path("purpose").asText()).isEqualTo("TEST");
        assertThat(body.path("subjectRef").asText()).hasSize(64);
        assertThat(body.path("grantedAt").isNull()).isFalse();
        assertThat(body.toString()).doesNotContain(msisdn).doesNotContain("msisdn");
        assertThat(created.getHeaders().getFirst(HttpHeaders.LOCATION))
                .isEqualTo("/v1/consents/" + consentId);
        assertThat(jdbc.queryForObject("select subject_ref from audit_event"
                        + " where action = 'CONSENT_GRANTED' and detail->>'consentId' = ?",
                String.class, consentId.toString()))
                .isEqualTo(body.path("subjectRef").asText());

        ResponseEntity<JsonNode> fetched = get("/v1/consents/" + consentId, key);
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody().path("subjectRef").asText())
                .isEqualTo(body.path("subjectRef").asText());

        ResponseEntity<JsonNode> revoked = delete("/v1/consents/" + consentId, key);
        assertThat(revoked.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<JsonNode> afterRevoke = get("/v1/consents/" + consentId, key);
        assertThat(afterRevoke.getBody().path("status").asText()).isEqualTo("REVOKED");
        assertThat(jdbc.queryForObject("select revoked_at is not null from consent"
                + " where consent_id = ?", Boolean.class, consentId)).isTrue();

        ResponseEntity<JsonNode> revokeAgain = delete("/v1/consents/" + consentId, key);
        assertThat(revokeAgain.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(revokeAgain.getBody().path("consentId").asText())
                .isEqualTo(consentId.toString());
    }

    @Test
    void regrantingKeepsOneLiveRow() {
        String key = newClientKey("consent-regrant");
        String msisdn = uniqueMsisdn();

        UUID first = UUID.fromString(post("/v1/consents",
                Map.of("msisdn", msisdn, "purpose", "TEST"), key)
                .getBody().path("consentId").asText());
        UUID second = UUID.fromString(post("/v1/consents",
                Map.of("msisdn", msisdn, "purpose", "TEST", "channel", "PORTAL"), key)
                .getBody().path("consentId").asText());

        assertThat(second).isEqualTo(first);
        assertThat(jdbc.queryForObject("select count(*) from consent where consent_id = ?",
                Integer.class, first)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select channel from consent where consent_id = ?",
                String.class, first)).isEqualTo("PORTAL");
    }

    @Test
    void aConsentForAnotherClientIsNotFound() {
        String owner = newClientKey("consent-owner");
        String other = newClientKey("consent-other");
        UUID consentId = UUID.fromString(post("/v1/consents",
                Map.of("msisdn", uniqueMsisdn(), "purpose", "TEST"), owner)
                .getBody().path("consentId").asText());

        ResponseEntity<JsonNode> response = get("/v1/consents/" + consentId, other);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().path("consentId").asText()).isEqualTo(consentId.toString());
        assertThat(delete("/v1/consents/" + consentId, other).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get("/v1/consents/" + consentId, owner).getBody().path("status").asText())
                .isEqualTo("GRANTED");
    }

    @Test
    void aPurposeTheClientDoesNotHoldCannotBeRecorded() {
        String key = newClientKey("consent-purpose");

        ResponseEntity<JsonNode> response = post("/v1/consents",
                Map.of("msisdn", uniqueMsisdn(), "purpose", "FLEET_MANAGEMENT"), key);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().path("code").asText()).isEqualTo("PURPOSE_NOT_ALLOWED");
    }

    @Test
    void oversizedEvidenceIsRefused() {
        String key = newClientKey("consent-evidence");

        ResponseEntity<JsonNode> response = post("/v1/consents", Map.of(
                "msisdn", uniqueMsisdn(),
                "purpose", "TEST",
                "evidence", Map.of("recording", "x".repeat(9_000))), key);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().path("code").asText()).isEqualTo("EVIDENCE_TOO_LARGE");
    }

    @Test
    void malformedGrantFieldsAreReportedByName() {
        String key = newClientKey("consent-fields");

        ResponseEntity<JsonNode> pastExpiry = post("/v1/consents", Map.of(
                "msisdn", uniqueMsisdn(), "purpose", "TEST",
                "expiresAt", "2020-01-01T00:00:00Z"), key);
        assertThat(pastExpiry.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(pastExpiry.getBody().path("fields").has("expiresAt")).isTrue();

        ResponseEntity<JsonNode> longChannel = post("/v1/consents", Map.of(
                "msisdn", uniqueMsisdn(), "purpose", "TEST",
                "channel", "ABCDEFGHIJKLMNOPQ"), key);
        assertThat(longChannel.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(longChannel.getBody().path("fields").has("channel")).isTrue();
    }

    @Test
    void anExpiredConsentDoesNotAuthoriseARequest() {
        String key = newClientKey("consent-expiry");
        String msisdn = uniqueMsisdn();
        UUID consentId = UUID.fromString(post("/v1/consents",
                Map.of("msisdn", msisdn, "purpose", "TEST",
                        "expiresAt", "2030-01-01T00:00:00Z"), key)
                .getBody().path("consentId").asText());

        jdbc.update("update consent set expires_at = now() - interval '1 hour' where consent_id = ?",
                consentId);

        ResponseEntity<JsonNode> response = post("/v1/locations",
                Map.of("msisdn", msisdn, "purpose", "TEST"), key);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().path("code").asText()).isEqualTo("PRIVACY_NOT_SATISFIED");
    }
}
