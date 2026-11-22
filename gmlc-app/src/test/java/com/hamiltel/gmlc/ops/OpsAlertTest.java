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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The alert engine judges recorded data, not a probe. Each test that needs
 * isolation gets its own tenant, so the shared embedded database cannot make
 * one test's history another test's alert.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "gmlc.ops.cookie-secure=false",
        "gmlc.ops.argon2-memory-kib=4096",
        "gmlc.ops.argon2-iterations=1",
        "gmlc.ops.environment=LAB",
        "gmlc.ops.slo-p95-ms=100",
        "gmlc.ops.alerts.enabled=true",
        "gmlc.ops.alerts.eval-seconds=3600",
        "gmlc.ops.alerts.window-minutes=120",
        "gmlc.ops.alerts.min-finished=1",
        "gmlc.ops.bootstrap-username=root",
        "gmlc.ops.bootstrap-password=" + OpsAlertTest.ADMIN_PASSWORD,
        "gmlc.ops.bootstrap-totp-secret=" + OpsAlertTest.ADMIN_TOTP
})
class OpsAlertTest {

    static final String ADMIN_PASSWORD = "bootstrap-password-123";
    static final String ADMIN_TOTP = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP";

    private static final AtomicLong TENANT_IDS = new AtomicLong(9000);

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    OpsAlertEngine engine;

    @Autowired
    OpsAlertRepository alerts;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        EmbeddedPostgresSupport.register(registry);
    }

    @BeforeEach
    void useJdkClient() {
        rest.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
    }

    @Test
    void aFailureRateRaisesOneAlertAndClosesWhenCleared() {
        long tenantId = newTenant();
        UUID clientId = newClient(tenantId);
        Instant now = Instant.now();

        seed(tenantId, clientId, "FAILED", "NO_SERVING_NODE", 50, now.minus(1, ChronoUnit.MINUTES));
        engine.evaluate(tenantId, now);

        List<OpsAlertRepository.AlertRow> open = alerts.list(tenantId,
                OpsAlertRepository.State.OPEN, 10);
        assertEquals(1, open.size(), "exactly the error-rate condition is observed");
        OpsAlertRepository.AlertRow raised = open.get(0);
        assertEquals(OpsAlertRepository.Kind.ERROR_RATE, raised.kind());
        assertNull(raised.closedAt());

        engine.evaluate(tenantId, now);
        List<OpsAlertRepository.AlertRow> afterRepeat = alerts.list(tenantId,
                OpsAlertRepository.State.OPEN, 10);
        assertEquals(1, afterRepeat.size(), "a repeated evaluation refreshes, it does not multiply");
        assertEquals(raised.alertId(), afterRepeat.get(0).alertId());

        for (int i = 0; i < 4; i++) {
            seed(tenantId, clientId, "DELIVERED", "OK", 50,
                    now.minus(30, ChronoUnit.SECONDS));
        }
        engine.evaluate(tenantId, now);

        assertTrue(alerts.list(tenantId, OpsAlertRepository.State.OPEN, 10).isEmpty(),
                "one failure in five finished requests is below the threshold");
        List<OpsAlertRepository.AlertRow> closed = alerts.list(tenantId,
                OpsAlertRepository.State.CLOSED, 10);
        assertEquals(1, closed.size());
        assertNotNull(closed.get(0).closedAt());
        assertEquals(OpsAlertRepository.Kind.ERROR_RATE, closed.get(0).kind());
    }

    @Test
    void latencyAboveTheConfiguredObjectiveRaisesAnAlert() {
        long tenantId = newTenant();
        UUID clientId = newClient(tenantId);
        Instant now = Instant.now();

        seed(tenantId, clientId, "DELIVERED", "OK", 500, now.minus(1, ChronoUnit.MINUTES));
        engine.evaluate(tenantId, now);

        OpsAlertRepository.AlertRow alert = alerts.list(tenantId,
                OpsAlertRepository.State.OPEN, 10).stream()
                .filter(row -> row.kind() == OpsAlertRepository.Kind.LATENCY_SLO)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no latency alert was raised"));

        JsonNode detail = alert.detail(new com.fasterxml.jackson.databind.ObjectMapper());
        assertEquals(100, detail.get("sloP95Ms").asInt());
        assertEquals(500.0, detail.get("p95ResponseMs").asDouble(), 0.001);
    }

    @Test
    void cellPlanMissesRaiseTheGapAlertWithTheirCount() {
        long tenantId = newTenant();
        UUID clientId = newClient(tenantId);
        Instant now = Instant.now();

        seed(tenantId, clientId, "FAILED", "CELL_NOT_IN_PLAN", 40, now.minus(1, ChronoUnit.MINUTES));
        engine.evaluate(tenantId, now);

        OpsAlertRepository.AlertRow gap = alerts.list(tenantId,
                OpsAlertRepository.State.OPEN, 10).stream()
                .filter(row -> row.kind() == OpsAlertRepository.Kind.CELL_DB_GAP)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no cell-gap alert was raised"));

        assertEquals("CELL_DB_GAP", gap.dedupeKey());
        JsonNode detail = gap.detail(new com.fasterxml.jackson.databind.ObjectMapper());
        assertEquals(1, detail.get("requests").asInt());
    }

    @Test
    void peersShowWhatWasObservedAndWhatWasNeverConfigured() {
        Session admin = signInAdmin();
        ResponseEntity<JsonNode> response = get(admin, "/v1/ops/peers");
        assertEquals(HttpStatus.OK, response.getStatusCode(), String.valueOf(response.getBody()));

        JsonNode body = response.getBody();
        assertEquals("LAB", body.get("environment").asText());
        assertEquals("LAB_SIM", body.get("dataSource").asText());
        assertEquals(3600, body.get("settings").get("evalSeconds").asLong());
        assertEquals(100, body.get("settings").get("sloP95Ms").asInt());

        for (JsonNode peer : body.get("peers")) {
            assertFalse(peer.get("configured").asBoolean(),
                    peer.get("face").asText() + " has no transport in this test");
            assertEquals("NOT_CONFIGURED", peer.get("state").asText());
            assertEquals(0, peer.get("attempts").asLong());
            assertFalse(peer.get("service").asText().isBlank(),
                    peer.get("face").asText() + " must name the 3GPP service it carries");
            assertFalse(peer.get("bearer").asText().isBlank(),
                    peer.get("face").asText() + " must name its bearer");
        }
        JsonNode diameter = null;
        for (JsonNode peer : body.get("peers")) {
            if ("DIAMETER".equals(peer.get("face").asText())) {
                diameter = peer;
            }
        }
        assertNotNull(diameter);
        assertEquals("SLh RIR / SLg PLR", diameter.get("service").asText());

        JsonNode interfaces = body.get("interfaces");
        assertEquals(4, interfaces.size(), "the 5G location interfaces are listed explicitly");
        for (JsonNode face : interfaces) {
            assertFalse(face.get("service").asText().isBlank());
            assertFalse(face.get("note").asText().isBlank());
        }
        assertEquals("NOT_APPLICABLE", stateOf(interfaces, "NL1"));
        assertEquals("NOT_BUILT", stateOf(interfaces, "NL2"));
        assertEquals("Namf_Location", serviceOf(interfaces, "NL2"));
        assertEquals("NOT_BUILT", stateOf(interfaces, "NL5"));
        assertEquals("NOT_BUILT", stateOf(interfaces, "NL6"));
    }

    private static String stateOf(JsonNode interfaces, String label) {
        for (JsonNode face : interfaces) {
            if (label.equals(face.get("label").asText())) {
                return face.get("state").asText();
            }
        }
        throw new AssertionError("no interface labelled " + label);
    }

    private static String serviceOf(JsonNode interfaces, String label) {
        for (JsonNode face : interfaces) {
            if (label.equals(face.get("label").asText())) {
                return face.get("service").asText();
            }
        }
        throw new AssertionError("no interface labelled " + label);
    }

    @Test
    void acknowledgeIsAuditedAndRefusesASecondAcknowledgement() {
        UUID clientId = newClient(1);
        Instant now = Instant.now();
        seed(1, clientId, "FAILED", "NO_SERVING_NODE", 50, now.minus(1, ChronoUnit.MINUTES));
        engine.evaluate(1, now);

        Session admin = signInAdmin();
        UUID adminId = UUID.fromString(admin.body().get("userId").asText());
        JsonNode list = get(admin, "/v1/ops/alerts?state=OPEN&limit=100").getBody();
        JsonNode errorRate = null;
        for (JsonNode alert : list.get("alerts")) {
            if ("ERROR_RATE".equals(alert.get("kind").asText())) {
                errorRate = alert;
            }
        }
        assertNotNull(errorRate, "the failure seed must raise an error-rate alert");
        long alertId = errorRate.get("alertId").asLong();
        assertFalse(errorRate.get("acknowledged").asBoolean());

        ResponseEntity<JsonNode> acknowledged = post(admin,
                "/v1/ops/alerts/" + alertId + "/acknowledge", admin.csrf());
        assertEquals(HttpStatus.OK, acknowledged.getStatusCode(),
                String.valueOf(acknowledged.getBody()));
        assertTrue(acknowledged.getBody().get("acknowledged").asBoolean());
        assertTrue(acknowledged.getBody().get("open").asBoolean(),
                "acknowledging does not close the condition");
        assertEquals(adminId.toString(),
                acknowledged.getBody().get("acknowledgedBy").asText());

        ResponseEntity<JsonNode> again = post(admin,
                "/v1/ops/alerts/" + alertId + "/acknowledge", admin.csrf());
        assertEquals(HttpStatus.CONFLICT, again.getStatusCode());
        assertEquals("ALREADY_ACKNOWLEDGED", again.getBody().get("code").asText());

        engine.evaluate(1, now);
        List<OpsAlertRepository.AlertRow> open = alerts.list(1,
                OpsAlertRepository.State.OPEN, 100).stream()
                .filter(row -> row.kind() == OpsAlertRepository.Kind.ERROR_RATE)
                .toList();
        assertEquals(1, open.size(), "re-evaluating keeps the acknowledged row");
        assertNotNull(open.get(0).acknowledgedAt(), "a refresh must not erase the acknowledgement");

        Long audits = jdbc.queryForObject("SELECT count(*) FROM audit_event"
                + " WHERE action = 'OPS_ALERT_ACKNOWLEDGED' AND outcome = 'ALLOWED'"
                + " AND actor_user_id = ?", Long.class, adminId);
        assertTrue(audits != null && audits >= 1);

        JsonNode summary = get(admin, "/v1/ops/summary?range=1h").getBody();
        assertTrue(summary.get("alerts").get("available").asBoolean());
        long openInDb = count("SELECT count(*) FROM ops_alert"
                + " WHERE tenant_id = 1 AND closed_at IS NULL");
        assertEquals(openInDb, summary.get("alerts").get("open").asLong());
        assertTrue(summary.get("alerts").get("acknowledgedOpen").asLong() >= 1);
    }

    @Test
    void acknowledgingNeedsThePermissionAndTheCsrfToken() {
        UUID clientId = newClient(1);
        Instant now = Instant.now();
        seed(1, clientId, "FAILED", "NO_SERVING_NODE", 50, now.minus(1, ChronoUnit.MINUTES));
        engine.evaluate(1, now);

        Session admin = signInAdmin();
        JsonNode list = get(admin, "/v1/ops/alerts?state=OPEN&limit=100").getBody();
        long alertId = list.get("alerts").get(0).get("alertId").asLong();

        ResponseEntity<JsonNode> withoutCsrf =
                post(admin, "/v1/ops/alerts/" + alertId + "/acknowledge", null);
        assertEquals(HttpStatus.FORBIDDEN, withoutCsrf.getStatusCode());
        assertEquals("CSRF_REQUIRED", withoutCsrf.getBody().get("code").asText());

        JsonNode created = createUser(admin,
                "alert-viewer-" + UUID.randomUUID().toString().substring(0, 8), "VIEWER");
        Session viewer = signIn(created.get("user").get("username").asText(),
                created.get("oneTimePassword").asText(), created.get("totpSecret").asText());
        ResponseEntity<JsonNode> denied = post(viewer,
                "/v1/ops/alerts/" + alertId + "/acknowledge", viewer.csrf());
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode());
        assertEquals("PERMISSION_DENIED", denied.getBody().get("code").asText());
        assertEquals("ACK_ALERTS", denied.getBody().get("permission").asText());
    }

    @Test
    void theAlertListValidatesItsStateAndLimit() {
        Session admin = signInAdmin();

        ResponseEntity<JsonNode> badState = get(admin, "/v1/ops/alerts?state=BOGUS");
        assertEquals(HttpStatus.BAD_REQUEST, badState.getStatusCode());
        assertEquals("ALERTS_STATE_NOT_SUPPORTED", badState.getBody().get("code").asText());

        ResponseEntity<JsonNode> badLimit = get(admin, "/v1/ops/alerts?limit=0");
        assertEquals(HttpStatus.BAD_REQUEST, badLimit.getStatusCode());
        assertEquals("LIMIT_OUT_OF_RANGE", badLimit.getBody().get("code").asText());

        ResponseEntity<JsonNode> missing = post(admin,
                "/v1/ops/alerts/999999999/acknowledge", admin.csrf());
        assertEquals(HttpStatus.NOT_FOUND, missing.getStatusCode());
    }

    // ---------------------------------------------------------------- helpers

    private long newTenant() {
        long tenantId = TENANT_IDS.incrementAndGet();
        jdbc.update("INSERT INTO tenant (tenant_id, name) VALUES (?, ?)",
                tenantId, "alert-test-" + tenantId);
        return tenantId;
    }

    private UUID newClient(long tenantId) {
        UUID clientId = UUID.randomUUID();
        jdbc.update("INSERT INTO api_client (client_id, tenant_id, name, key_hash, purposes)"
                        + " VALUES (?, ?, ?, ?, ARRAY['TEST'])",
                clientId, tenantId, "alert-" + clientId, hex64(clientId));
        return clientId;
    }

    private void seed(long tenantId, UUID clientId, String state, String resultCode,
                      int responseMs, Instant at) {
        UUID requestId = UUID.randomUUID();
        jdbc.update("INSERT INTO location_request (request_id, tenant_id, client_id, purpose,"
                        + " subscriber_msisdn, requested_accuracy_m, requested_priority, state,"
                        + " protocol, created_at, updated_at)"
                        + " VALUES (?, ?, ?, 'TEST', '256771234567', 1000, 'NORMAL', ?, 'MAP', ?, ?)",
                requestId, tenantId, clientId, state, Timestamp.from(at), Timestamp.from(at));
        String recordStatus = "FAILED".equals(state) ? "FAILED" : "ANSWERED";
        jdbc.update("INSERT INTO cdr (tenant_id, request_id, client_id, subscriber_ref,"
                        + " result_code, accuracy_requested_m, protocol, response_time_ms,"
                        + " record_status, methods_attempted, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, 1000, 'MAP', ?, ?, ARRAY[]::text[], ?)",
                tenantId, requestId, clientId, hex64(requestId), resultCode, responseMs,
                recordStatus, Timestamp.from(at));
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
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

    private ResponseEntity<JsonNode> post(Session session, String path, String csrf) {
        return rest.exchange(path, HttpMethod.POST,
                new HttpEntity<>(sessionHeaders(session, csrf)), JsonNode.class);
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
