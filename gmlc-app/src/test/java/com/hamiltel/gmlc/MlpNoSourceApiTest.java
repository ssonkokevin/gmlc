package com.hamiltel.gmlc;

import com.hamiltel.gmlc.support.EmbeddedPostgresSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * With no signalling peer the MLP answer names the standard "misconfiguration
 * of location server" result and the Le record keeps the same fact. A client
 * never receives a coordinate the gateway did not obtain.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MlpNoSourceApiTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        EmbeddedPostgresSupport.register(registry);
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    private static String uniqueMsisdn() {
        return "2567" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
    }

    private String newClient() {
        Map<String, Object> body = Map.of(
                "name", "mlp-nosource-" + UUID.randomUUID().toString().substring(0, 8),
                "purposes", List.of("TEST"));
        ResponseEntity<com.fasterxml.jackson.databind.JsonNode> response = rest.exchange(
                "/v1/admin/clients", HttpMethod.POST,
                new HttpEntity<>(body, EmbeddedPostgresSupport.adminHeaders()),
                com.fasterxml.jackson.databind.JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().path("apiKey").asText();
    }

    @Test
    void withConsentButNoPeerTheMlpAnswerIsAMisconfigurationResult() {
        String key = newClient();
        String msisdn = uniqueMsisdn();
        HttpHeaders consent = EmbeddedPostgresSupport.bearer(key);
        consent.setContentType(MediaType.APPLICATION_JSON);
        assertThat(rest.exchange("/v1/consents", HttpMethod.POST,
                new HttpEntity<>(Map.of("msisdn", msisdn, "purpose", "TEST", "channel", "WEB"),
                        consent), String.class).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        String xml = """
                <?xml version="1.0"?>
                <svc_init ver="3.2.0">
                  <hdr ver="3.2.0"><client><id>theasp</id><serviceid>TEST</serviceid></client></hdr>
                  <slir ver="3.2.0"><msids><msid>%s</msid></msids></slir>
                </svc_init>
                """.formatted(msisdn);
        HttpHeaders headers = EmbeddedPostgresSupport.bearer(key);
        headers.setContentType(MediaType.APPLICATION_XML);

        ResponseEntity<String> response = rest.exchange("/mlp", HttpMethod.POST,
                new HttpEntity<>(xml, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .contains("<result resid=\"605\">MISCONFIGURATION OF LOCATION SERVER</result>")
                .contains("NO_LCS_SOURCE")
                .doesNotContain("<pd>")
                .doesNotContain("SQL");

        assertThat(jdbc.queryForObject("select state from location_request"
                + " where subscriber_msisdn = ?", String.class, msisdn)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select error_code from location_request"
                + " where subscriber_msisdn = ?", String.class, msisdn)).isEqualTo("NO_LCS_SOURCE");
    }
}
