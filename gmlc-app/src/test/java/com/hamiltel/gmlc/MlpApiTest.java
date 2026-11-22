package com.hamiltel.gmlc;

import com.hamiltel.gmlc.location.LocationEstimate;
import com.hamiltel.gmlc.location.LocationLocator;
import com.hamiltel.gmlc.support.EmbeddedPostgresSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The MLP face is a translation over Le, and these tests hold it to that: the
 * same API key authenticates, consent decides (602), the requested accuracy is
 * bounded by the client's entitlement (601), a successful answer is a circle
 * with its radius and confidence, and a service this deployment does not
 * implement is refused by name (108) instead of being half-answered.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(MlpApiTest.FixedLocator.class)
class MlpApiTest {

    @TestConfiguration
    static class FixedLocator {

        @Bean
        @Primary
        LocationLocator fixedLocator() {
            return new LocationLocator() {
                @Override
                public Optional<LocationEstimate> locate(Query query) {
                    return Optional.of(new LocationEstimate("LTE", "CELL_ID", "CIRCLE",
                            new BigDecimal("0.347600"), new BigDecimal("32.582500"),
                            350, 70, 80, 0, true, query.accuracyMeters(),
                            "001010000000001", "SMLC", "MME", Instant.now(),
                            List.of("MAP_ATI")));
                }

                @Override
                public String protocol() {
                    return "MAP";
                }
            };
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        EmbeddedPostgresSupport.register(registry);
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private org.springframework.boot.web.client.RestTemplateBuilder restTemplateBuilder;

    @org.springframework.boot.test.web.server.LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    private TestRestTemplate httpClient() {
        TestRestTemplate client = new TestRestTemplate(restTemplateBuilder
                .requestFactory(settings ->
                        new org.springframework.http.client.JdkClientHttpRequestFactory()));
        client.setUriTemplateHandler(
                new org.springframework.web.util.DefaultUriBuilderFactory(
                        "http://localhost:" + port));
        return client;
    }

    private static String uniqueMsisdn() {
        return "2567" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
    }

    private String newClient(int maxAccuracyM) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("name", "mlp-" + java.util.UUID.randomUUID().toString().substring(0, 8));
        body.put("purposes", List.of("TEST"));
        body.put("maxAccuracyM", maxAccuracyM);
        ResponseEntity<com.fasterxml.jackson.databind.JsonNode> response = rest.exchange(
                "/v1/admin/clients", HttpMethod.POST,
                new HttpEntity<>(body, EmbeddedPostgresSupport.adminHeaders()),
                com.fasterxml.jackson.databind.JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().path("apiKey").asText();
    }

    private ResponseEntity<String> mlp(String xml, String apiKey) {
        HttpHeaders headers = EmbeddedPostgresSupport.bearer(apiKey);
        headers.setContentType(MediaType.APPLICATION_XML);
        return rest.exchange("/mlp", HttpMethod.POST, new HttpEntity<>(xml, headers), String.class);
    }

    private static String slir(String serviceId, String msid, int accuracy) {
        String header = serviceId == null ? "" : "<hdr ver=\"3.2.0\"><client><id>theasp</id>"
                + "<serviceid>" + serviceId + "</serviceid></client></hdr>";
        return """
                <?xml version="1.0"?>
                <!DOCTYPE svc_init SYSTEM "MLP_SVC_INIT_320.DTD">
                <svc_init ver="3.2.0">
                  %s
                  <slir ver="3.2.0">
                    <msids><msid type="MSISDN">%s</msid></msids>
                    <eqop><hor_acc>%d</hor_acc></eqop>
                    <prio type="HIGH"/>
                    <loc_type type="CURRENT"/>
                  </slir>
                </svc_init>
                """.formatted(header, msid, accuracy);
    }

    private static void grantConsent(TestRestTemplate rest, String apiKey, String msisdn) {
        HttpHeaders headers = EmbeddedPostgresSupport.bearer(apiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = rest.exchange("/v1/consents", HttpMethod.POST,
                new HttpEntity<>(Map.of("msisdn", msisdn, "purpose", "TEST", "channel", "WEB"),
                        headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void withoutConsentTheAnswerIsPositioningNotAllowedAndNothingIsRecorded() {
        String key = newClient(5_000);
        String msisdn = uniqueMsisdn();

        ResponseEntity<String> response = mlp(slir("TEST", msisdn, 5_000), key);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType())
                .isEqualTo(MediaType.APPLICATION_XML);
        assertThat(response.getBody())
                .contains("<slia ver=\"3.0.0\">")
                .contains("<msid type=\"MSISDN\" enc=\"ASC\">" + msisdn + "</msid>")
                .contains("<result resid=\"602\">POSITIONING NOT ALLOWED</result>")
                .contains("no consent covers this subject for that purpose")
                .doesNotContain("SQL");
        assertThat(jdbc.queryForObject("select count(*) from location_request"
                + " where subscriber_msisdn = ?", Integer.class, msisdn)).isZero();
    }

    @Test
    void withConsentTheAnswerIsACircleWithItsUncertaintyAndConfidence() {
        String key = newClient(5_000);
        String msisdn = uniqueMsisdn();
        grantConsent(rest, key, msisdn);

        ResponseEntity<String> response = mlp(slir("TEST", msisdn, 5_000), key);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .contains("<slia ver=\"3.0.0\">")
                .contains("<X>0 20 51.360N</X>")
                .contains("<Y>32 34 57.000E</Y>")
                .contains("<radius>350</radius>")
                .contains("<lev_conf>70</lev_conf>")
                .doesNotContain("qos_not_met")
                .doesNotContain("<poserr>");

        Map<String, Object> stored = jdbc.queryForMap(
                "select request_id, state, error_code from location_request"
                        + " where subscriber_msisdn = ?", msisdn);
        assertThat(stored.get("state")).isEqualTo("DELIVERED");
        assertThat(stored.get("error_code")).isNull();
        java.util.UUID requestId = (java.util.UUID) stored.get("request_id");
        assertThat(jdbc.queryForObject("select result_code from cdr where request_id = ?",
                String.class, requestId)).isEqualTo("OK");
        assertThat(jdbc.queryForObject("select record_status from cdr where request_id = ?",
                String.class, requestId)).isEqualTo("ANSWERED");
        assertThat(jdbc.queryForObject("select count(*) from audit_event"
                + " where action = 'REQUEST_ANSWERED' and request_id = ?",
                Integer.class, requestId)).isEqualTo(1);
    }

    @Test
    void anAccuracyTheClientBuysButTheNetworkCannotMeetIsMarkedQosNotMet() {
        String key = newClient(100);
        String msisdn = uniqueMsisdn();
        grantConsent(rest, key, msisdn);

        ResponseEntity<String> response = mlp(slir("TEST", msisdn, 100), key);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .contains("<radius>350</radius>")
                .contains("<qos_not_met/>");
    }

    @Test
    void anAccuracyBeyondTheEntitlementIsQopNotAttainable() {
        String key = newClient(5_000);
        String msisdn = uniqueMsisdn();

        ResponseEntity<String> response = mlp(slir("TEST", msisdn, 10), key);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .contains("<result resid=\"601\">QOP NOT ATTAINABLE</result>")
                .contains("may not ask for better than 5000 m");
    }

    @Test
    void aPurposeTheClientDoesNotHoldIsUnauthorizedApplication() {
        String key = newClient(5_000);
        String msisdn = uniqueMsisdn();

        ResponseEntity<String> response = mlp(slir("SAFETY", msisdn, 1_000), key);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .contains("<result resid=\"3\">UNAUTHORIZED APPLICATION</result>")
                .contains("not registered for that purpose");
    }

    @Test
    void aNumericServiceIdFallsBackToTheClientSolePurpose() {
        String key = newClient(5_000);
        String msisdn = uniqueMsisdn();
        grantConsent(rest, key, msisdn);

        ResponseEntity<String> response = mlp(slir("0005", msisdn, 5_000), key);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("<radius>350</radius>");
    }

    @Test
    void aForeignImsiIsUnknownSubscriber() {
        String key = newClient(5_000);
        String imsi = "999700000000001";

        String xml = """
                <?xml version="1.0"?>
                <svc_init ver="3.2.0">
                  <hdr ver="3.2.0"><client><id>theasp</id><serviceid>TEST</serviceid></client></hdr>
                  <slir ver="3.2.0">
                    <msids><msid type="IMSI">%s</msid></msids>
                  </slir>
                </svc_init>
                """.formatted(imsi);

        ResponseEntity<String> response = mlp(xml, key);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .contains("<result resid=\"4\">UNKNOWN SUBSCRIBER</result>")
                .contains("IMSI_NOT_IN_TENANT_PLMN");
    }

    @Test
    void malformedXmlIsSyntaxErrorAndNeverAServerError() {
        String key = newClient(5_000);

        ResponseEntity<String> response = mlp("<svc_init ver=\"3.2.0\"><slir>", key);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .contains("<result resid=\"106\">SYNTAX ERROR</result>")
                .doesNotContain("Exception");
    }

    @Test
    void aDoctypeThatNamesAnExternalDtdIsNotFetchedAndStillAnswers() {
        String key = newClient(5_000);
        String msisdn = uniqueMsisdn();
        grantConsent(rest, key, msisdn);

        String xml = """
                <?xml version="1.0"?>
                <!DOCTYPE svc_init SYSTEM "http://127.0.0.1:1/should-never-be-fetched.dtd">
                <svc_init ver="3.2.0"><slir ver="3.2.0">
                  <msids><msid>%s</msid></msids>
                </slir></svc_init>
                """.formatted(msisdn);

        ResponseEntity<String> response = mlp(xml, key);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("<radius>350</radius>");
    }

    @Test
    void triggeredReportingIsRefusedByNameAndAudited() {
        String key = newClient(5_000);
        String msisdn = uniqueMsisdn();
        int before = jdbc.queryForObject("select count(*) from tracking_schedule", Integer.class);

        String xml = """
                <?xml version="1.0"?>
                <svc_init ver="3.2.0">
                  <hdr ver="3.2.0"><client><id>theasp</id><serviceid>TEST</serviceid></client></hdr>
                  <tlrr ver="3.2.0">
                    <msids><msid>%s</msid></msids>
                    <interval>00000300</interval>
                  </tlrr>
                </svc_init>
                """.formatted(msisdn);

        ResponseEntity<String> response = mlp(xml, key);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .contains("<tlra ver=\"3.2.0\">")
                .contains("<result resid=\"108\">SERVICE NOT SUPPORTED</result>")
                .contains("tlrr service is not implemented");
        assertThat(jdbc.queryForObject("select count(*) from tracking_schedule", Integer.class))
                .isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from audit_event"
                + " where action = 'MLP_SERVICE_REFUSED' and outcome = 'DENIED'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void oneSubjectWithoutConsentDoesNotTakeTheAnswerFromTheOther() {
        String key = newClient(5_000);
        String consented = uniqueMsisdn();
        String refused = uniqueMsisdn();
        grantConsent(rest, key, consented);

        String xml = """
                <?xml version="1.0"?>
                <svc_init ver="3.2.0">
                  <hdr ver="3.2.0"><client><id>theasp</id><serviceid>TEST</serviceid></client></hdr>
                  <slir ver="3.2.0">
                    <msids>
                      <msid>%s</msid>
                      <msid>%s</msid>
                    </msids>
                    <eqop><hor_acc>5000</hor_acc></eqop>
                  </slir>
                </svc_init>
                """.formatted(consented, refused);

        ResponseEntity<String> response = mlp(xml, key);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .contains("<msid type=\"MSISDN\" enc=\"ASC\">" + consented
                        + "</msid><pd><time")
                .contains("<radius>350</radius>")
                .contains("<msid type=\"MSISDN\" enc=\"ASC\">" + refused
                        + "</msid><poserr>")
                .contains("<result resid=\"602\">POSITIONING NOT ALLOWED</result>");
    }

    @Test
    void moreThanTenSubjectsIsTooManyPositionItems() {
        String key = newClient(5_000);
        StringBuilder msids = new StringBuilder();
        for (int i = 0; i < 11; i++) {
            msids.append("<msid>").append(uniqueMsisdn()).append("</msid>");
        }
        String xml = """
                <svc_init ver="3.2.0"><slir ver="3.2.0"><msids>%s</msids></slir></svc_init>
                """.formatted(msids);

        ResponseEntity<String> response = mlp(xml, key);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .contains("<result resid=\"104\">TOO MANY POSITION ITEMS</result>")
                .contains("at most 10 msids");
    }

    @Test
    void withoutAnApiKeyTheRequestIsUnauthorized() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_XML);
        ResponseEntity<String> response = httpClient().exchange("/mlp", HttpMethod.POST,
                new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("a valid API key is required");
    }
}
