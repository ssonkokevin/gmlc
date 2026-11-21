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
 * Monitoring reads the CDR, and the reports group the same stored rows the
 * dashboard counts. The tests seed three exchanges written directly, which
 * makes each expected figure checkable by hand: two answered, one failed.
 *
 * <p>Export is a separate permission and a separate audit action. A role
 * without it is refused by the interceptor, and the file never carries a
 * subscriber identifier.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "gmlc.ops.cookie-secure=false",
        "gmlc.ops.argon2-memory-kib=4096",
        "gmlc.ops.argon2-iterations=1",
        "gmlc.ops.environment=LAB",
        "gmlc.ops.bootstrap-username=root",
        "gmlc.ops.bootstrap-password=" + OpsMonitoringTest.ADMIN_PASSWORD,
        "gmlc.ops.bootstrap-totp-secret=" + OpsMonitoringTest.ADMIN_TOTP,
        "gmlc.ops.alerts.enabled=false"
})
class OpsMonitoringTest {

    static final String ADMIN_PASSWORD = "bootstrap-password-123";
    static final String ADMIN_TOTP = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP";

    private static final String PURPOSE = "COMMERCIAL_LBS";

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcTemplate jdbc;

    private UUID alphaClient;
    private UUID betaClient;
    private String alphaName;
    private String betaName;
    private UUID answeredRequest;
    private UUID failedRequest;
    private UUID secondAnsweredRequest;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        EmbeddedPostgresSupport.register(registry);
    }

    @BeforeEach
    void seed() {
        rest.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
        // The embedded database is shared across this JVM's test classes, so start
        // from an empty request history and make every count below exact.
        jdbc.update("DELETE FROM cdr");
        jdbc.update("DELETE FROM location_result");
        jdbc.update("DELETE FROM location_request");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        alphaName = "Monitoring Alpha " + suffix;
        betaName = "=Monitor Beta " + suffix;
        alphaClient = seedClient(alphaName);
        betaClient = seedClient(betaName);

        Instant now = Instant.now();
        answeredRequest = seedRequest(alphaClient, "DELIVERED", "MAP", now.minus(5, ChronoUnit.MINUTES));
        failedRequest = seedRequest(alphaClient, "FAILED", "DIAMETER", now.minus(10, ChronoUnit.MINUTES));
        secondAnsweredRequest = seedRequest(betaClient, "DELIVERED", "MAP", now.minus(15, ChronoUnit.MINUTES));

        seedAnsweredCdr(answeredRequest, alphaClient, "MAP", now.minus(5, ChronoUnit.MINUTES),
                120, 200, true);
        seedFailedCdr(failedRequest, alphaClient, "DIAMETER", now.minus(10, ChronoUnit.MINUTES));
        seedAnsweredCdr(secondAnsweredRequest, betaClient, "MAP", now.minus(15, ChronoUnit.MINUTES),
                340, 400, false);
        seedResult(answeredRequest, "UGA-001", 200, 4);
        seedResult(secondAnsweredRequest, "UGA-002", 400, 9);
    }

    @Test
    void exchangesAreTheStoredCdrAndSayThatRawPayloadsAreNotCaptured() {
        Session admin = signInAdmin();

        ResponseEntity<JsonNode> page = get(admin, "/v1/ops/exchanges?range=1h");
        assertEquals(HttpStatus.OK, page.getStatusCode(), String.valueOf(page.getBody()));
        assertEquals(3, page.getBody().get("total").asLong());
        assertFalse(page.getBody().get("rawPayload").get("available").asBoolean());
        assertTrue(page.getBody().get("rawPayload").get("reason").asText().contains("not captured"));
        assertTrue(page.getBody().get("items").get(0).get("methodsAttempted").isArray());
        assertEquals(3, page.getBody().get("items").size());

        assertEquals(1, get(admin, "/v1/ops/exchanges?range=1h&recordStatus=TIMEOUT")
                .getBody().get("total").asLong());
        assertEquals(2, get(admin, "/v1/ops/exchanges?range=1h&protocol=MAP")
                .getBody().get("total").asLong());
        assertEquals(1, get(admin, "/v1/ops/exchanges?range=1h&requestId=" + failedRequest)
                .getBody().get("total").asLong());

        ResponseEntity<JsonNode> badLimit = get(admin, "/v1/ops/exchanges?range=1h&limit=500");
        assertEquals(HttpStatus.BAD_REQUEST, badLimit.getStatusCode());
        assertEquals("LIMIT_OUT_OF_RANGE", badLimit.getBody().get("code").asText());

        ResponseEntity<JsonNode> noSession = rest.getForEntity("/v1/ops/exchanges", JsonNode.class);
        assertEquals(HttpStatus.UNAUTHORIZED, noSession.getStatusCode());
    }

    @Test
    void theSeriesCountsTheSameRowsTheListShows() {
        Session admin = signInAdmin();
        ResponseEntity<JsonNode> series =
                get(admin, "/v1/ops/exchanges/series?range=1h&bucket=15m");
        assertEquals(HttpStatus.OK, series.getStatusCode(), String.valueOf(series.getBody()));

        JsonNode totals = series.getBody().get("totals");
        assertEquals(3, totals.get("finished").asLong());
        assertEquals(2, totals.get("answered").asLong());
        assertEquals(1, totals.get("failed").asLong());
        assertFalse(totals.get("p95ResponseMs").isNull());
        assertTrue(series.getBody().get("points").size() > 0);

        JsonNode byProtocol = series.getBody().get("byProtocol");
        assertEquals(2, byProtocol.size());
        assertEquals("MAP", byProtocol.get(0).get("name").asText());
        assertEquals(2, byProtocol.get(0).get("count").asLong());
    }

    @Test
    void exportIsSeparatePermissionSeparatelyAuditedAndFreeOfIdentifiers() {
        Session admin = signInAdmin();
        Session analyst = signInAsNewUser(admin, "ANALYST");

        ResponseEntity<String> exported = exchangeCsv(analyst, "/v1/ops/exchanges/export.csv?range=1h");
        assertEquals(HttpStatus.OK, exported.getStatusCode(), exported.getBody());
        assertTrue(exported.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE).startsWith("text/csv"));
        assertEquals("3", exported.getHeaders().getFirst("X-Export-Rows"));
        assertEquals("false", exported.getHeaders().getFirst("X-Export-Truncated"));

        String[] lines = exported.getBody().strip().split("\\r?\\n");
        assertEquals(4, lines.length, exported.getBody());
        assertTrue(lines[0].startsWith("\"cdr_id\",\"created_at\""));
        assertTrue(exported.getBody().contains(failedRequest.toString()));
        assertTrue(exported.getBody().contains("\"'=Monitor Beta"),
                "a leading = must be neutralised so the file cannot act as a formula");
        assertFalse(exported.getBody().contains("subscriber"));

        Integer audited = jdbc.queryForObject("SELECT count(*) FROM audit_event"
                        + " WHERE action = 'OPS_CDR_EXPORTED' AND outcome = 'ALLOWED'"
                        + " AND actor_user_id = ?::uuid AND detail->>'rows' = '3'",
                Integer.class, analyst.body().get("userId").asText());
        assertEquals(1, audited);

        Session operator = signInAsNewUser(admin, "OPERATOR");
        ResponseEntity<String> refused = exchangeCsv(operator, "/v1/ops/exchanges/export.csv?range=1h");
        assertEquals(HttpStatus.FORBIDDEN, refused.getStatusCode());
        Integer denied = jdbc.queryForObject("SELECT count(*) FROM audit_event"
                        + " WHERE action = 'OPS_FORBIDDEN' AND outcome = 'DENIED'"
                        + " AND actor_user_id = ?::uuid AND detail->>'permission' = 'EXPORT_CDR'",
                Integer.class, operator.body().get("userId").asText());
        assertEquals(1, denied);
    }

    @Test
    void reportsGroupTheStoredRowsByClientPurposeRatMethodAndCell() {
        Session admin = signInAdmin();
        Session viewer = signInAsNewUser(admin, "VIEWER");

        ResponseEntity<JsonNode> reports = get(viewer, "/v1/ops/reports?range=1h");
        assertEquals(HttpStatus.OK, reports.getStatusCode(), String.valueOf(reports.getBody()));

        JsonNode totals = reports.getBody().get("totals");
        assertEquals(3, totals.get("requests").asLong());
        assertEquals(2, totals.get("answered").asLong());
        assertEquals(1, totals.get("failed").asLong());
        assertEquals(0, totals.get("inFlight").asLong());

        JsonNode alpha = byName(reports.getBody().get("byClient"), alphaName);
        assertNotNull(alpha, reports.getBody().get("byClient").toString());
        assertEquals(2, alpha.get("requests").asLong());
        assertEquals(1, alpha.get("answered").asLong());
        assertEquals(0.5, alpha.get("successRate").asDouble(), 0.0001);
        assertEquals(120.0, alpha.get("averageResponseMs").asDouble(), 0.01);

        JsonNode purpose = byName(reports.getBody().get("byPurpose"), PURPOSE);
        assertEquals(3, purpose.get("requests").asLong());

        JsonNode lte = byName(reports.getBody().get("byRat"), "LTE");
        assertEquals(2, lte.get("total").asLong());
        assertEquals(2, lte.get("answered").asLong());
        JsonNode gsm = byName(reports.getBody().get("byRat"), "GSM");
        assertEquals(1, gsm.get("failed").asLong());

        JsonNode cellId = byName(reports.getBody().get("byMethod"), "CELL_ID");
        assertEquals(2, cellId.get("count").asLong());
        assertNotNull(cellId.get("averageUncertaintyM"));

        JsonNode cellA = byName(reports.getBody().get("byCell"), "UGA-001");
        assertEquals(1, cellA.get("results").asLong());
        assertEquals(200.0, cellA.get("averageUncertaintyM").asDouble(), 0.01);
        assertEquals(1.0, cellA.get("accuracyFulfilledRate").asDouble(), 0.0001);

        assertEquals(3, reports.getBody().get("notes").size());
    }

    @Test
    void reportExportNeedsExportCdrAndRejectsAnUnknownSection() {
        Session admin = signInAdmin();
        Session analyst = signInAsNewUser(admin, "ANALYST");
        Session operator = signInAsNewUser(admin, "OPERATOR");

        ResponseEntity<String> clients =
                exchangeCsv(analyst, "/v1/ops/reports/export.csv?range=1h&report=clients");
        assertEquals(HttpStatus.OK, clients.getStatusCode(), clients.getBody());
        assertTrue(clients.getBody().contains("\"name\",\"requests\""));
        assertTrue(clients.getBody().contains("\"'=Monitor Beta"));
        Integer audited = jdbc.queryForObject("SELECT count(*) FROM audit_event"
                        + " WHERE action = 'OPS_REPORT_EXPORTED' AND outcome = 'ALLOWED'"
                        + " AND actor_user_id = ?::uuid AND detail->>'report' = 'clients'",
                Integer.class, analyst.body().get("userId").asText());
        assertEquals(1, audited);

        ResponseEntity<String> refused =
                exchangeCsv(operator, "/v1/ops/reports/export.csv?range=1h&report=clients");
        assertEquals(HttpStatus.FORBIDDEN, refused.getStatusCode());

        ResponseEntity<String> unknown = exchangeCsv(admin,
                "/v1/ops/reports/export.csv?range=1h&report=nonsense");
        assertEquals(HttpStatus.BAD_REQUEST, unknown.getStatusCode());
        assertTrue(unknown.getBody().contains("REPORT_NOT_SUPPORTED"), unknown.getBody());
    }

    // ---------------------------------------------------------------- helpers

    private UUID seedClient(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO api_client (client_id, tenant_id, name, key_hash, purposes,"
                        + " max_accuracy_m, enabled) VALUES (?, 1, ?, ?, ARRAY[?], 300, TRUE)",
                id, name, hex64(id), PURPOSE);
        return id;
    }

    private UUID seedRequest(UUID clientId, String state, String protocol, Instant createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO location_request (request_id, tenant_id, client_id, purpose,"
                        + " subscriber_msisdn, requested_accuracy_m, requested_priority, state,"
                        + " rat, serving_node, positioning_node, error_code, protocol,"
                        + " created_at, updated_at, completed_at)"
                        + " VALUES (?, 1, ?, ?, '256772000111', 300, 'NORMAL', ?, 'LTE',"
                        + " 'MME-1', 'LMF-1', ?, ?, ?, ?, ?)",
                id, clientId, PURPOSE, state,
                "FAILED".equals(state) ? "NO_LCS_SOURCE" : null, protocol,
                Timestamp.from(createdAt), Timestamp.from(createdAt), Timestamp.from(createdAt));
        return id;
    }

    private void seedAnsweredCdr(UUID requestId, UUID clientId, String protocol, Instant createdAt,
                                 int responseTimeMs, int uncertaintyM, boolean accuracyFulfilled) {
        jdbc.update("INSERT INTO cdr (tenant_id, request_id, client_id, subscriber_ref, rat_type,"
                        + " positioning_method, methods_attempted, result_code,"
                        + " accuracy_requested_m, accuracy_achieved_m, accuracy_fulfilled,"
                        + " response_time_ms, positioning_node, serving_node, age_of_location_s,"
                        + " paging_performed, shape, latitude, longitude, uncertainty_m,"
                        + " confidence, record_status, created_at, protocol, operation)"
                        + " VALUES (1, ?, ?, ?, 'LTE', 'CELL_ID', ARRAY['CELL_ID'], 'OK', 300, ?,"
                        + " ?, ?, 'LMF-1', 'MME-1', 4, FALSE, 'CIRCLE', 0.3476, 32.5825, ?, 80,"
                        + " 'ANSWERED', ?, ?, 'PROVIDE_LOCATION')",
                requestId, clientId, hex64(requestId), uncertaintyM, accuracyFulfilled,
                responseTimeMs, uncertaintyM, Timestamp.from(createdAt), protocol);
    }

    private void seedFailedCdr(UUID requestId, UUID clientId, String protocol, Instant createdAt) {
        jdbc.update("INSERT INTO cdr (tenant_id, request_id, client_id, subscriber_ref, rat_type,"
                        + " methods_attempted, result_code, response_time_ms, paging_performed,"
                        + " record_status, created_at, protocol)"
                        + " VALUES (1, ?, ?, ?, 'GSM', ARRAY['CELL_ID'], 'TIMEOUT', 5000, FALSE,"
                        + " 'TIMEOUT', ?, ?)",
                requestId, clientId, hex64(requestId), Timestamp.from(createdAt), protocol);
    }

    private void seedResult(UUID requestId, String cell, int uncertaintyM, int ageSeconds) {
        jdbc.update("INSERT INTO location_result (result_id, tenant_id, request_id, method,"
                        + " methods_attempted, shape, latitude, longitude, uncertainty_m,"
                        + " confidence, age_seconds, accuracy_requested_m, accuracy_fulfilled,"
                        + " serving_cell, engine, positioning_node, produced_at)"
                        + " VALUES (?, 1, ?, 'CELL_ID', ARRAY['CELL_ID'], 'CIRCLE', 0.3476,"
                        + " 32.5825, ?, 80, ?, 300, ?, ?, 'CELL_DB', 'LMF-1', now())",
                UUID.randomUUID(), requestId, uncertaintyM, ageSeconds,
                uncertaintyM <= 300, cell);
    }

    private JsonNode byName(JsonNode rows, String name) {
        for (JsonNode row : rows) {
            if (name.equals(row.get("name").asText())) {
                return row;
            }
        }
        return null;
    }

    private ResponseEntity<JsonNode> get(Session session, String path) {
        return rest.exchange(path, HttpMethod.GET,
                new HttpEntity<>(sessionHeaders(session)), JsonNode.class);
    }

    private ResponseEntity<String> exchangeCsv(Session session, String path) {
        HttpHeaders headers = sessionHeaders(session);
        headers.setAccept(java.util.List.of(MediaType.parseMediaType("text/csv")));
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private Session signInAdmin() {
        return signIn("root", ADMIN_PASSWORD, ADMIN_TOTP);
    }

    private Session signInAsNewUser(Session admin, String role) {
        String username = role.toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("displayName", "Test " + role);
        body.put("role", role);
        ResponseEntity<JsonNode> created = rest.exchange("/v1/ops/users", HttpMethod.POST,
                new HttpEntity<>(body, sessionHeaders(admin, admin.csrf())), JsonNode.class);
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), String.valueOf(created.getBody()));
        return signIn(username, created.getBody().get("oneTimePassword").asText(),
                created.getBody().get("totpSecret").asText());
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

    private static HttpHeaders sessionHeaders(Session session) {
        return sessionHeaders(session, null);
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

    private static String hex64(UUID uuid) {
        String base = uuid.toString().replace("-", "");
        return (base + base).substring(0, 64);
    }

    private record Session(String token, String csrf, JsonNode body) {
    }
}
