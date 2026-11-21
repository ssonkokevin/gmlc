package com.hamiltel.gmlc.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.hamiltel.gmlc.support.EmbeddedPostgresSupport;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dashboard's data honesty: the figures it shows are the figures the
 * database holds, identifiers are masked until a permitted reveal, an
 * unbuilt feature says so, and the surface refuses an API key and an
 * unauthenticated caller alike.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "gmlc.ops.cookie-secure=false",
        "gmlc.ops.argon2-memory-kib=4096",
        "gmlc.ops.argon2-iterations=1",
        "gmlc.ops.environment=LAB",
        "gmlc.ops.bootstrap-username=root",
        "gmlc.ops.bootstrap-password=" + OpsDashboardTest.ADMIN_PASSWORD,
        "gmlc.ops.bootstrap-totp-secret=" + OpsDashboardTest.ADMIN_TOTP
})
class OpsDashboardTest {

    static final String ADMIN_PASSWORD = "bootstrap-password-123";
    static final String ADMIN_TOTP = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP";

    private static final String MSISDN = "256771234567";

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcTemplate jdbc;

    private UUID clientId;
    private UUID answeredMap;
    private UUID answeredDiameter;
    private UUID failedMap;
    private UUID inFlight;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        EmbeddedPostgresSupport.register(registry);
    }

    @BeforeEach
    void seed() {
        rest.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());

        clientId = UUID.randomUUID();
        jdbc.update("INSERT INTO api_client (client_id, tenant_id, name, key_hash, purposes)"
                        + " VALUES (?, 1, ?, ?, ARRAY['TEST'])",
                clientId, "dash-" + clientId.toString().substring(0, 8), hex64(clientId));

        Instant now = Instant.now();
        answeredMap = seedRequest(clientId, "DELIVERED", now.minus(30, ChronoUnit.MINUTES), "MAP");
        answeredDiameter = seedRequest(clientId, "DELIVERED", now.minus(20, ChronoUnit.MINUTES),
                "DIAMETER");
        failedMap = seedRequest(clientId, "FAILED", now.minus(10, ChronoUnit.MINUTES), "MAP");
        inFlight = seedRequest(clientId, "REQUESTED", now.minus(1, ChronoUnit.MINUTES), "MAP");

        seedAnsweredCdr(answeredMap, "MAP", "SRILCS_PSL", 200, 300, true,
                now.minus(30, ChronoUnit.MINUTES));
        seedAnsweredCdr(answeredDiameter, "DIAMETER", "RIR_PLA", 400, 5000, false,
                now.minus(20, ChronoUnit.MINUTES));
        seedFailedCdr(failedMap, "MAP", "NO_SERVING_NODE", 50,
                now.minus(10, ChronoUnit.MINUTES));

        jdbc.update("INSERT INTO audit_event (tenant_id, actor_client_id, action, request_id,"
                        + " outcome, detail, occurred_at) VALUES (1, ?, 'REQUEST_ACCEPTED', ?,"
                        + " 'ALLOWED', '{\"purpose\":\"TEST\"}'::jsonb, ?)",
                clientId, answeredMap, Timestamp.from(now.minus(30, ChronoUnit.MINUTES)));
        jdbc.update("INSERT INTO audit_event (tenant_id, actor_client_id, action, request_id,"
                        + " outcome, detail, occurred_at) VALUES (1, ?, 'REQUEST_ANSWERED', ?,"
                        + " 'ALLOWED', '{\"method\":\"CELL_ID\"}'::jsonb, ?)",
                clientId, answeredMap, Timestamp.from(now.minus(29, ChronoUnit.MINUTES)));
    }

    @Test
    void summaryFiguresEqualTheDatabase() {
        Session admin = signInAdmin();
        ResponseEntity<JsonNode> response = get(admin, "/v1/ops/summary?range=1h");
        assertEquals(HttpStatus.OK, response.getStatusCode(), String.valueOf(response.getBody()));
        JsonNode summary = response.getBody();

        long requests = count("SELECT count(*) FROM location_request"
                + " WHERE tenant_id = 1 AND created_at >= now() - interval '1 hour'");
        long answered = count("SELECT count(*) FROM cdr WHERE tenant_id = 1"
                + " AND record_status = 'ANSWERED' AND created_at >= now() - interval '1 hour'");
        long finished = count("SELECT count(*) FROM cdr WHERE tenant_id = 1"
                + " AND created_at >= now() - interval '1 hour'");
        Double average = jdbc.queryForObject("SELECT avg(response_time_ms)::float8 FROM cdr"
                        + " WHERE tenant_id = 1 AND record_status = 'ANSWERED'"
                        + " AND created_at >= now() - interval '1 hour'",
                Double.class);
        Double p95 = jdbc.queryForObject("SELECT percentile_cont(0.95) WITHIN GROUP"
                        + " (ORDER BY response_time_ms) FROM cdr WHERE tenant_id = 1"
                        + " AND record_status = 'ANSWERED' AND created_at >= now() - interval '1 hour'",
                Double.class);

        assertEquals(requests, summary.get("requests").asLong());
        assertEquals(finished, summary.get("finished").asLong());
        assertEquals(answered, summary.get("answered").asLong());
        assertEquals(requests - finished, summary.get("inFlight").asLong());
        assertEquals(average, summary.get("averageResponseMs").asDouble(), 0.000001);
        assertEquals(p95, summary.get("p95ResponseMs").asDouble(), 0.000001);

        long seriesRequests = 0;
        for (JsonNode point : summary.get("series")) {
            seriesRequests += point.get("requests").asLong();
        }
        assertEquals(requests, seriesRequests, "the chart must add up to the reported total");

        assertEquals("LAB", summary.get("environment").asText());
        assertEquals("LAB_SIM", summary.get("dataSource").asText());
        assertFalse(summary.get("deferred").get("available").asBoolean());
        assertFalse(summary.get("alerts").get("available").asBoolean());
        assertFalse(summary.get("licence").get("available").asBoolean());
        assertFalse(summary.get("deferred").get("reason").asText().isBlank());
        assertTrue(summary.path("sloP95Ms").isMissingNode() || summary.path("sloP95Ms").isNull(),
                "no SLO is configured for this deployment");
    }

    @Test
    void throughputSeparatesMapFromDiameter() {
        Session admin = signInAdmin();
        JsonNode throughput = get(admin, "/v1/ops/throughput?range=1h&bucket=5m").getBody();

        long map = count("SELECT count(*) FROM location_request WHERE tenant_id = 1"
                + " AND protocol = 'MAP' AND created_at >= now() - interval '1 hour'");
        long diameter = count("SELECT count(*) FROM location_request WHERE tenant_id = 1"
                + " AND protocol = 'DIAMETER' AND created_at >= now() - interval '1 hour'");

        assertEquals(map, throughput.get("totals").get("map").asLong());
        assertEquals(diameter, throughput.get("totals").get("diameter").asLong());
        assertEquals(map + diameter, throughput.get("totals").get("total").asLong()
                - throughput.get("totals").get("unattributed").asLong());
        assertEquals(300, throughput.get("bucketSeconds").asLong());
    }

    @Test
    void anUnknownOperationIsReportedAsUnattributed() {
        Session admin = signInAdmin();
        JsonNode mix = get(admin, "/v1/ops/mix?range=1h").getBody();

        boolean unattributed = false;
        for (JsonNode row : mix.get("byOperation")) {
            if ("UNATTRIBUTED".equals(row.get("name").asText())) {
                unattributed = true;
            }
        }
        assertTrue(unattributed, "a CDR without an operation must not be guessed into one");
        assertFalse(mix.get("fallback").get("available").asBoolean());
    }

    @Test
    void requestsAreMaskedUntilAPermittedReveal() {
        Session admin = signInAdmin();
        JsonNode page = get(admin, "/v1/ops/requests?range=1h&clientId=" + clientId).getBody();

        assertEquals(4, page.get("total").asLong());
        assertTrue(page.get("identifiersMasked").asBoolean());
        JsonNode first = page.get("items").get(0);
        assertFalse(page.toString().contains(MSISDN), "no raw identifier may appear in a list");
        assertTrue(first.get("msisdn").asText().contains("*"));

        JsonNode detail = get(admin, "/v1/ops/requests/" + answeredMap + "?reveal=true").getBody();
        assertEquals(MSISDN, detail.get("identifiers").get("msisdn").asText());
        assertFalse(detail.get("identifiers").get("masked").asBoolean());

        Long reveals = jdbc.queryForObject("SELECT count(*) FROM audit_event"
                + " WHERE action = 'OPS_IDENTIFIER_REVEAL' AND outcome = 'ALLOWED'"
                + " AND actor_user_id = ?", Long.class,
                UUID.fromString(admin.body().get("userId").asText()));
        assertTrue(reveals != null && reveals >= 1);
    }

    @Test
    void aViewerCannotRevealIdentifiers() {
        Session admin = signInAdmin();
        JsonNode created = createUser(admin, "viewer-" + UUID.randomUUID().toString().substring(0, 8),
                "VIEWER");
        Session viewer = signIn(created.get("user").get("username").asText(),
                created.get("oneTimePassword").asText(), created.get("totpSecret").asText());

        ResponseEntity<JsonNode> detail = get(viewer,
                "/v1/ops/requests/" + answeredMap + "?reveal=true");
        assertEquals(HttpStatus.FORBIDDEN, detail.getStatusCode());
        assertEquals("PERMISSION_DENIED", detail.getBody().get("code").asText());
        assertFalse(detail.toString().contains(MSISDN));

        Long denied = jdbc.queryForObject("SELECT count(*) FROM audit_event"
                + " WHERE action = 'OPS_IDENTIFIER_REVEAL' AND outcome = 'DENIED'"
                + " AND actor_user_id = ?", Long.class,
                UUID.fromString(viewer.body().get("userId").asText()));
        assertTrue(denied != null && denied >= 1);
    }

    @Test
    void theDetailCarriesTheRecordedTimelineAndEnvelope() {
        Session admin = signInAdmin();
        JsonNode detail = get(admin, "/v1/ops/requests/" + answeredMap).getBody();

        assertEquals("DELIVERED", detail.get("state").asText());
        assertEquals("MAP", detail.get("protocol").asText());
        assertEquals("CELL_ID", detail.get("result").get("method").asText());
        assertEquals(300, detail.get("result").get("uncertaintyM").asInt());
        assertEquals(80, detail.get("result").get("confidence").asInt());
        assertEquals(0, detail.get("result").get("ageSeconds").asInt());
        assertEquals("SRILCS_PSL", detail.get("cdr").get("operation").asText());

        boolean received = false;
        boolean answered = false;
        for (JsonNode event : detail.get("timeline")) {
            received |= "RECEIVED".equals(event.get("action").asText())
                    && "REQUEST".equals(event.get("source").asText());
            answered |= "REQUEST_ANSWERED".equals(event.get("action").asText())
                    && "AUDIT".equals(event.get("source").asText());
        }
        assertTrue(received);
        assertTrue(answered);
    }

    @Test
    void theListFiltersByOutcomeAndPages() {
        Session admin = signInAdmin();
        JsonNode answered = get(admin, "/v1/ops/requests?range=1h&clientId=" + clientId
                + "&recordStatus=ANSWERED&limit=1&offset=0").getBody();
        assertEquals(2, answered.get("total").asLong());
        assertEquals(1, answered.get("items").size());

        JsonNode second = get(admin, "/v1/ops/requests?range=1h&clientId=" + clientId
                + "&recordStatus=ANSWERED&limit=1&offset=1").getBody();
        assertEquals(2, second.get("total").asLong());
        assertFalse(answered.get("items").get(0).get("requestId").asText()
                .equals(second.get("items").get(0).get("requestId").asText()));
    }

    @Test
    void deferredAndAlertsSayWhatIsMissing() {
        Session admin = signInAdmin();
        JsonNode deferred = get(admin, "/v1/ops/deferred").getBody();
        assertFalse(deferred.get("available").asBoolean());
        assertFalse(deferred.get("reason").asText().isBlank());
        assertTrue(deferred.get("open").asLong() >= 1);
        assertNotNull(jdbc.queryForObject("SELECT count(*) FROM location_request"
                + " WHERE request_id = ? AND state = 'REQUESTED'", Long.class, inFlight));
    }

    @Test
    void theSurfaceRefusesAnApiKeyAndAnUnAuthenticatedCaller() {
        assertEquals(HttpStatus.UNAUTHORIZED,
                rest.getForEntity("/v1/ops/summary", JsonNode.class).getStatusCode());

        JsonNode client = EmbeddedPostgresSupport.registerClient(rest, "ops-dash-key");
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(client.get("apiKey").asText());
        ResponseEntity<JsonNode> withKey = rest.exchange("/v1/ops/summary", HttpMethod.GET,
                new HttpEntity<>(headers), JsonNode.class);
        assertEquals(HttpStatus.UNAUTHORIZED, withKey.getStatusCode());
    }

    @Test
    void badRangesAndBucketsAreRejectedWithACode() {
        Session admin = signInAdmin();
        ResponseEntity<JsonNode> range = get(admin, "/v1/ops/summary?range=2h");
        assertEquals(HttpStatus.BAD_REQUEST, range.getStatusCode());
        assertEquals("RANGE_NOT_SUPPORTED", range.getBody().get("code").asText());

        ResponseEntity<JsonNode> bucket = get(admin, "/v1/ops/summary?range=30d&bucket=1m");
        assertEquals(HttpStatus.BAD_REQUEST, bucket.getStatusCode());
        assertEquals("BUCKET_TOO_SMALL", bucket.getBody().get("code").asText());

        ResponseEntity<JsonNode> limit = get(admin, "/v1/ops/requests?range=1h&limit=500");
        assertEquals(HttpStatus.BAD_REQUEST, limit.getStatusCode());
        assertEquals("LIMIT_OUT_OF_RANGE", limit.getBody().get("code").asText());
    }

    // ---------------------------------------------------------------- helpers

    private UUID seedRequest(UUID client, String state, Instant at, String protocol) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO location_request (request_id, tenant_id, client_id, purpose,"
                        + " subscriber_msisdn, requested_accuracy_m, requested_priority, state,"
                        + " protocol, created_at, updated_at) VALUES (?, 1, ?, 'TEST', ?, 1000,"
                        + " 'NORMAL', ?, ?, ?, ?)",
                id, client, MSISDN, state, protocol, Timestamp.from(at), Timestamp.from(at));
        return id;
    }

    private void seedAnsweredCdr(UUID requestId, String protocol, String operation, int responseMs,
                                 int uncertaintyM, boolean fulfilled, Instant at) {
        UUID resultId = UUID.randomUUID();
        jdbc.update("INSERT INTO location_result (result_id, tenant_id, request_id, method,"
                        + " methods_attempted, shape, latitude, longitude, uncertainty_m, confidence,"
                        + " age_seconds, accuracy_requested_m, accuracy_fulfilled, serving_cell,"
                        + " geometry_data_quality, engine, positioning_node, produced_at)"
                        + " VALUES (?, 1, ?, 'CELL_ID', ARRAY['MAP_SRILCS','MAP_ATI'], 'CIRCLE',"
                        + " ?, ?, ?, 80, 0, 1000, ?, '001-01-1', 60, 'CELL_DB', 'mme-1', ?)",
                resultId, requestId, new BigDecimal("0.3471000"), new BigDecimal("32.5825000"),
                uncertaintyM, fulfilled, Timestamp.from(at));
        jdbc.update("UPDATE location_request SET result_id = ? WHERE request_id = ?",
                resultId, requestId);

        jdbc.update("INSERT INTO cdr (tenant_id, request_id, client_id, subscriber_ref, rat_type,"
                        + " positioning_method, methods_attempted, result_code, accuracy_requested_m,"
                        + " accuracy_achieved_m, accuracy_fulfilled, response_time_ms,"
                        + " positioning_node, serving_node, age_of_location_s, shape, latitude,"
                        + " longitude, uncertainty_m, confidence, record_status, protocol,"
                        + " operation, created_at)"
                        + " VALUES (1, ?, ?, ?, 'LTE', 'CELL_ID', ARRAY['MAP_SRILCS','MAP_ATI'],"
                        + " 'OK', 1000, ?, ?, ?, 'mme-1', 'mme-1', 0, 'CIRCLE', ?, ?, ?, 80,"
                        + " 'ANSWERED', ?, ?, ?)",
                requestId, clientId, hex64(requestId), uncertaintyM, fulfilled, responseMs,
                new BigDecimal("0.3471000"), new BigDecimal("32.5825000"), uncertaintyM,
                protocol, operation, Timestamp.from(at));
    }

    private void seedFailedCdr(UUID requestId, String protocol, String code, int responseMs,
                               Instant at) {
        jdbc.update("INSERT INTO cdr (tenant_id, request_id, client_id, subscriber_ref,"
                        + " result_code, accuracy_requested_m, protocol, response_time_ms,"
                        + " record_status, methods_attempted, created_at)"
                        + " VALUES (1, ?, ?, ?, ?, 1000, ?, ?, 'FAILED', ARRAY[]::text[], ?)",
                requestId, clientId, hex64(requestId), code, protocol, responseMs,
                Timestamp.from(at));
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    private static String hex64(UUID uuid) {
        String base = uuid.toString().replace("-", "");
        return (base + base).substring(0, 64);
    }

    private ResponseEntity<JsonNode> get(Session session, String path) {
        return rest.exchange(path, HttpMethod.GET,
                new HttpEntity<>(sessionHeaders(session, null)), JsonNode.class);
    }

    private Session signInAdmin() {
        return signIn("root", ADMIN_PASSWORD, ADMIN_TOTP);
    }

    private Session signIn(String username, String password, String secret) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("password", password);
        body.put("totp", Totp.code(secret, Instant.now()));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<JsonNode> response = rest.postForEntity("/v1/ops/session",
                new HttpEntity<>(body, headers), JsonNode.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(), String.valueOf(response.getBody()));
        String setCookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertNotNull(setCookie);
        String token = setCookie.substring("GMLC_OPS=".length(), setCookie.indexOf(';'));
        return new Session(token, response.getBody().get("csrfToken").asText(), response.getBody());
    }

    private JsonNode createUser(Session admin, String username, String role) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("displayName", "Test " + username);
        body.put("role", role);
        ResponseEntity<JsonNode> response = rest.exchange("/v1/ops/users", HttpMethod.POST,
                new HttpEntity<>(body, sessionHeaders(admin, admin.csrf())), JsonNode.class);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(), String.valueOf(response.getBody()));
        return response.getBody();
    }

    private static HttpHeaders sessionHeaders(Session session, String csrf) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add(HttpHeaders.COOKIE, "GMLC_OPS=" + session.token());
        if (csrf != null) {
            headers.set(OpsSessionFilter.CSRF_HEADER, csrf);
        }
        return headers;
    }

    private record Session(String token, String csrf, JsonNode body) {
    }
}
