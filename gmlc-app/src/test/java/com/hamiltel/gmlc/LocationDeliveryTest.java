package com.hamiltel.gmlc;

import com.fasterxml.jackson.databind.JsonNode;
import com.hamiltel.gmlc.diameter.SignallingException;
import com.hamiltel.gmlc.location.LocationEstimate;
import com.hamiltel.gmlc.location.LocationLocator;
import com.hamiltel.gmlc.support.EmbeddedPostgresSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a client sees when the gateway can answer. The locator is stubbed so
 * the test is about the request path, not the signalling: an answer must be
 * stored before it is returned and must carry its uncertainty, confidence and
 * age; a refusal must be a code with a CDR saying the same, never a position.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(LocationDeliveryTest.StubLocator.class)
class LocationDeliveryTest {

    enum Mode { ANSWER, EMPTY, REFUSE }

    static volatile Mode mode = Mode.ANSWER;

    @TestConfiguration
    static class StubLocator {

        @Bean
        @Primary
        LocationLocator stubLocationLocator() {
            return query -> switch (mode) {
                case ANSWER -> Optional.of(answer(query));
                case EMPTY -> Optional.empty();
                case REFUSE -> throw new SignallingException("POSITIONING_DENIED",
                        "the network refused the privacy check");
            };
        }

        private static LocationEstimate answer(LocationLocator.Query query) {
            boolean fulfilled = query.accuracyMeters() == null || 2_500 <= query.accuracyMeters();
            return new LocationEstimate("LTE", "CELL_ID", "CIRCLE",
                    new BigDecimal("0.3476000"), new BigDecimal("32.5825000"),
                    2_500, 80, 80, 45, fulfilled, query.accuracyMeters(),
                    "001-01-1-662273", "CELL_DB",
                    "mme1.epc.mnc001.mcc001.3gppnetwork.org", Instant.now(),
                    List.of("SLH_RIR", "SLG_PLA"));
        }
    }

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

    @AfterEach
    void reset() {
        mode = Mode.ANSWER;
    }

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
    void anAnswerIsStoredWithItsEnvelopeBeforeItIsReturned() {
        String key = newClientKey("le-answer");
        String msisdn = uniqueMsisdn();
        grantConsent(key, Map.of("msisdn", msisdn, "purpose", "TEST", "channel", "WEB"));

        ResponseEntity<JsonNode> created = post("/v1/locations",
                Map.of("msisdn", msisdn, "purpose", "TEST", "accuracyMeters", 10_000), key);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = created.getBody();
        assertThat(body.path("state").asText()).isEqualTo("DELIVERED");
        assertThat(body.has("errorCode")).isFalse();
        assertThat(body.toString()).doesNotContain(msisdn).doesNotContain("001010000000001");

        JsonNode result = body.path("result");
        assertThat(result.path("method").asText()).isEqualTo("CELL_ID");
        assertThat(result.path("shape").asText()).isEqualTo("CIRCLE");
        assertThat(result.path("latitude").asDouble()).isEqualTo(0.3476);
        assertThat(result.path("longitude").asDouble()).isEqualTo(32.5825);
        assertThat(result.path("uncertaintyM").asInt()).isEqualTo(2_500);
        assertThat(result.path("confidence").asInt()).isEqualTo(80);
        assertThat(result.path("ageSeconds").asInt()).isEqualTo(45);
        assertThat(result.path("accuracyRequestedM").asInt()).isEqualTo(10_000);
        assertThat(result.path("accuracyFulfilled").asBoolean()).isTrue();
        assertThat(result.path("servingCell").asText()).isEqualTo("001-01-1-662273");
        assertThat(result.path("engine").asText()).isEqualTo("CELL_DB");
        assertThat(result.path("positioningNode").asText())
                .isEqualTo("mme1.epc.mnc001.mcc001.3gppnetwork.org");
        assertThat(result.path("methodsAttempted").toString()).contains("SLH_RIR", "SLG_PLA");

        UUID requestId = UUID.fromString(body.path("requestId").asText());
        assertThat(jdbc.queryForObject("select state from location_request where request_id = ?",
                String.class, requestId)).isEqualTo("DELIVERED");
        UUID resultId = jdbc.queryForObject(
                "select result_id from location_request where request_id = ?", UUID.class, requestId);
        assertThat(resultId).isNotNull();
        assertThat(jdbc.queryForObject("select count(*) from location_result where request_id = ?",
                Integer.class, requestId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select uncertainty_m from location_result where result_id = ?",
                Integer.class, resultId)).isEqualTo(2_500);

        assertThat(jdbc.queryForObject("select record_status from cdr where request_id = ?",
                String.class, requestId)).isEqualTo("ANSWERED");
        assertThat(jdbc.queryForObject("select result_code from cdr where request_id = ?",
                String.class, requestId)).isEqualTo("OK");
        assertThat(jdbc.queryForObject("select accuracy_achieved_m from cdr where request_id = ?",
                Integer.class, requestId)).isEqualTo(2_500);
        assertThat(jdbc.queryForObject("select positioning_method from cdr where request_id = ?",
                String.class, requestId)).isEqualTo("CELL_ID");

        ResponseEntity<JsonNode> fetched = EmbeddedPostgresSupport.get(rest,
                "/v1/locations/" + requestId, key);
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody().path("state").asText()).isEqualTo("DELIVERED");
        assertThat(fetched.getBody().path("result").path("uncertaintyM").asInt()).isEqualTo(2_500);
    }

    @Test
    void aNetworkThatNamesNoCellFailsClosedAndSaysWhy() {
        mode = Mode.EMPTY;
        String key = newClientKey("le-empty");
        String msisdn = uniqueMsisdn();
        grantConsent(key, Map.of("msisdn", msisdn, "purpose", "TEST", "channel", "WEB"));

        ResponseEntity<JsonNode> created = post("/v1/locations",
                Map.of("msisdn", msisdn, "purpose", "TEST"), key);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = created.getBody();
        assertThat(body.path("state").asText()).isEqualTo("FAILED");
        assertThat(body.path("errorCode").asText()).isEqualTo("NO_LCS_SOURCE");
        assertThat(body.path("errorDetail").asText()).contains("named no cell");
        assertThat(body.has("result")).isFalse();

        UUID requestId = UUID.fromString(body.path("requestId").asText());
        assertThat(jdbc.queryForObject("select count(*) from location_result where request_id = ?",
                Integer.class, requestId)).isZero();
        assertThat(jdbc.queryForObject("select record_status from cdr where request_id = ?",
                String.class, requestId)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select result_code from cdr where request_id = ?",
                String.class, requestId)).isEqualTo("NO_LCS_SOURCE");
    }

    @Test
    void aRefusalKeepsItsCodeAndNeverBecomesAPosition() {
        mode = Mode.REFUSE;
        String key = newClientKey("le-refused");
        String msisdn = uniqueMsisdn();
        grantConsent(key, Map.of("msisdn", msisdn, "purpose", "TEST", "channel", "WEB"));

        ResponseEntity<JsonNode> created = post("/v1/locations",
                Map.of("msisdn", msisdn, "purpose", "TEST"), key);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = created.getBody();
        assertThat(body.path("state").asText()).isEqualTo("FAILED");
        assertThat(body.path("errorCode").asText()).isEqualTo("POSITIONING_DENIED");
        assertThat(body.path("errorDetail").asText()).contains("refused the privacy check");
        assertThat(body.has("result")).isFalse();
        assertThat(body.has("latitude")).isFalse();

        UUID requestId = UUID.fromString(body.path("requestId").asText());
        assertThat(jdbc.queryForObject("select result_code from cdr where request_id = ?",
                String.class, requestId)).isEqualTo("POSITIONING_DENIED");
        assertThat(jdbc.queryForObject("select count(*) from location_result where request_id = ?",
                Integer.class, requestId)).isZero();
    }
}
