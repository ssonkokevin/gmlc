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

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The emergency override is a deployment switch, never a client option: with
 * it on, EMERGENCY requests proceed without stored consent and the audit row
 * says so. No other purpose is affected.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "gmlc.emergency-override=true")
class EmergencyOverrideTest {

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

    private String newClientKey() {
        Map<String, Object> body = Map.of(
                "name", "emergency-" + UUID.randomUUID().toString().substring(0, 8),
                "purposes", List.of("EMERGENCY", "TEST"),
                "ratePerSecond", 1000,
                "ratePerDay", 100_000);

        ResponseEntity<JsonNode> response = http().exchange("/v1/admin/clients", HttpMethod.POST,
                new HttpEntity<>(body, EmbeddedPostgresSupport.adminHeaders()), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().path("apiKey").asText();
    }

    private ResponseEntity<JsonNode> requestLocation(String apiKey, String purpose, String msisdn) {
        HttpHeaders headers = EmbeddedPostgresSupport.bearer(apiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http().exchange("/v1/locations", HttpMethod.POST,
                new HttpEntity<>(Map.of("msisdn", msisdn, "purpose", purpose), headers),
                JsonNode.class);
    }

    private static String uniqueMsisdn() {
        return "2567" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
    }

    @Test
    void emergencyProceedsWithoutConsentAndSaysWhichBasisWasUsed() {
        String key = newClientKey();

        ResponseEntity<JsonNode> response = requestLocation(key, "EMERGENCY", uniqueMsisdn());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(body.path("state").asText()).isEqualTo("FAILED");
        assertThat(body.path("errorCode").asText()).isEqualTo("NO_LCS_SOURCE");

        UUID requestId = UUID.fromString(body.path("requestId").asText());
        assertThat(jdbc.queryForObject("select detail->>'basis' from audit_event"
                        + " where action = 'REQUEST_ACCEPTED' and request_id = ?",
                String.class, requestId))
                .isEqualTo("EMERGENCY_OVERRIDE");
        assertThat(jdbc.queryForObject("select consent_id from cdr where request_id = ?",
                UUID.class, requestId)).isNull();
    }

    @Test
    void theOverrideDoesNotCoverOtherPurposes() {
        String key = newClientKey();

        ResponseEntity<JsonNode> response = requestLocation(key, "TEST", uniqueMsisdn());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().path("code").asText()).isEqualTo("PRIVACY_NOT_SATISFIED");
    }
}
