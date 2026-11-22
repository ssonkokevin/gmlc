package com.hamiltel.gmlc.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.hamiltel.gmlc.support.EmbeddedPostgresSupport;
import org.junit.jupiter.api.AfterEach;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Users and access: the role matrix, live sessions, the audit view, the
 * sign-in policy, the client register and break-glass. Elevation is proven by
 * behaviour - the elevated session reads a page its role cannot read, and the
 * same operator's other session does not.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "gmlc.ops.cookie-secure=false",
        "gmlc.ops.argon2-memory-kib=4096",
        "gmlc.ops.argon2-iterations=1",
        "gmlc.ops.bootstrap-username=root",
        "gmlc.ops.bootstrap-password=" + OpsAccessTest.ADMIN_PASSWORD,
        "gmlc.ops.bootstrap-totp-secret=" + OpsAccessTest.ADMIN_TOTP,
        "gmlc.ops.alerts.enabled=false",
        "gmlc.webhooks.enabled=false",
        "gmlc.webhooks.initial-delay-seconds=3600",
        "gmlc.tracking.initial-delay-seconds=3600"
})
class OpsAccessTest {

    static final String ADMIN_PASSWORD = "bootstrap-password-123";
    static final String ADMIN_TOTP = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP";
    static final String EMERGENCY_CODE = "sealed-emergency-code-example";
    static final String EMERGENCY_HASH = OpsSessionService.sha256(EMERGENCY_CODE);

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcTemplate jdbc;

    private final List<UUID> createdUsers = new ArrayList<>();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        EmbeddedPostgresSupport.register(registry);
        registry.add("gmlc.ops.break-glass-code-hash", () -> EMERGENCY_HASH);
    }

    @BeforeEach
    void reset() {
        rest.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
        jdbc.update("DELETE FROM ops_break_glass");
    }

    @AfterEach
    void cleanUp() {
        // The embedded database is shared by every context in the run. Only
        // this class' grants and accounts are removed: leaving an extra
        // administrator behind would move the last-admin rule for another test.
        jdbc.update("DELETE FROM ops_break_glass");
        for (UUID userId : createdUsers) {
            jdbc.update("DELETE FROM ops_session WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM ops_user WHERE user_id = ?", userId);
        }
        createdUsers.clear();
    }

    @Test
    void theRoleMatrixAndPolicyDescribeWhatIsActuallyEnforced() {
        Session root = signInAdmin();

        JsonNode matrix = get(root, "/v1/ops/access/roles").getBody();
        assertEquals(13, matrix.get("permissions").size());
        assertEquals(4, matrix.get("roles").size());
        JsonNode admin = role(matrix, "ADMIN");
        assertNotNull(admin);
        assertTrue(admin.get("permissions").toString().contains("MANAGE_USERS"));
        JsonNode operator = role(matrix, "OPERATOR");
        assertNotNull(operator);
        assertTrue(operator.get("permissions").toString().contains("LOCATE"));
        assertFalse(operator.get("permissions").toString().contains("MANAGE_USERS"));
        JsonNode viewer = role(matrix, "VIEWER");
        assertEquals(List.of("VIEW_DASHBOARD", "VIEW_REQUESTS"),
                List.of(viewer.get("permissions").get(0).asText(),
                        viewer.get("permissions").get(1).asText()));

        JsonNode policy = get(root, "/v1/ops/access/policy").getBody();
        assertTrue(policy.get("mfaRequired").asBoolean());
        assertEquals(12, policy.get("passwordMinLength").asInt());
        assertEquals(6, policy.get("totpDigits").asInt());
        assertEquals(30, policy.get("totpPeriodSeconds").asInt());
        assertEquals("ARGON2ID", policy.get("passwordHash").asText());
        assertEquals(4096, policy.get("argon2MemoryKib").asInt());
        assertEquals(30, policy.get("breakGlassMinutes").asInt());
        assertTrue(policy.get("breakGlassConfigured").asBoolean());
        assertEquals("ADMIN", policy.get("breakGlassScope").asText());

        Session operatorSession = signInAsNewUser(root, "OPERATOR");
        ResponseEntity<JsonNode> refused = get(operatorSession, "/v1/ops/access/roles");
        assertEquals(HttpStatus.FORBIDDEN, refused.getStatusCode());
        assertEquals("PERMISSION_DENIED", refused.getBody().get("code").asText());
    }

    @Test
    void sessionsAcrossOperatorsAreListedAndOneCanBeEnded() {
        Session root = signInAdmin();
        Session operator = signInAsNewUser(root, "OPERATOR");

        JsonNode rows = get(root, "/v1/ops/access/sessions").getBody();
        assertTrue(rows.size() >= 2);
        JsonNode current = null;
        JsonNode other = null;
        for (JsonNode row : rows) {
            if (row.get("current").asBoolean()) {
                current = row;
            } else if (operator.body().get("username").asText().equals(row.get("username").asText())) {
                other = row;
            }
        }
        assertNotNull(current);
        assertNotNull(other);
        assertEquals("root", current.get("username").asText());

        ResponseEntity<Void> ended = rest.exchange(
                "/v1/ops/access/sessions/" + other.get("sessionId").asText() + "/sign-out",
                HttpMethod.POST,
                new HttpEntity<>(sessionHeaders(root, root.csrf())), Void.class);
        assertEquals(HttpStatus.NO_CONTENT, ended.getStatusCode());

        assertEquals(HttpStatus.UNAUTHORIZED, get(operator, "/v1/ops/me").getStatusCode());
        assertEquals(HttpStatus.OK, get(root, "/v1/ops/me").getStatusCode());

        Integer audited = jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE action = 'OPS_SESSION_REVOKED'"
                        + " AND detail->>'reason' = 'ADMIN_SIGN_OUT' AND detail->>'targetUser' = ?",
                Integer.class, operator.body().get("userId").asText());
        assertEquals(1, audited);
    }

    @Test
    void theAuditViewIsFilteredAndCarriesNoPosition() {
        Session root = signInAdmin();
        signInAsNewUser(root, "VIEWER");

        ResponseEntity<JsonNode> created = get(root,
                "/v1/ops/access/audit?action=OPS_USER_CREATED&limit=10");
        assertEquals(HttpStatus.OK, created.getStatusCode(), String.valueOf(created.getBody()));
        assertFalse(created.getBody().isEmpty());
        for (JsonNode row : created.getBody()) {
            assertEquals("OPS_USER_CREATED", row.get("action").asText());
            assertEquals("OPERATOR", row.get("actorType").asText());
            assertEquals("root", row.get("actorUsername").asText());
        }

        JsonNode denied = get(root,
                "/v1/ops/access/audit?outcome=DENIED&actor=root&limit=50").getBody();
        for (JsonNode row : denied) {
            assertEquals("DENIED", row.get("outcome").asText());
            assertEquals("root", row.get("actorUsername").asText());
        }

        JsonNode ranged = get(root, "/v1/ops/access/audit?from="
                + Instant.now().minusSeconds(600) + "&to=" + Instant.now().plusSeconds(60)
                + "&limit=5").getBody();
        assertFalse(ranged.isEmpty());

        String payload = created.getBody().toString().toLowerCase(java.util.Locale.ROOT);
        for (String forbidden : List.of("\"latitude\"", "\"longitude\"", "\"lat\"",
                "\"lon\"", "\"msisdn\"", "\"imsi\"")) {
            assertFalse(payload.contains(forbidden), "audit view exposed " + forbidden);
        }

        assertEquals("AUDIT_OUTCOME_NOT_SUPPORTED",
                get(root, "/v1/ops/access/audit?outcome=MAYBE").getBody().get("code").asText());
        assertEquals("AUDIT_TIME_INVALID",
                get(root, "/v1/ops/access/audit?from=yesterday").getBody().get("code").asText());
        assertEquals("AUDIT_RANGE_INVALID",
                get(root, "/v1/ops/access/audit?from=2026-01-02T00:00:00Z"
                        + "&to=2026-01-01T00:00:00Z").getBody().get("code").asText());
        assertEquals("LIMIT_OUT_OF_RANGE",
                get(root, "/v1/ops/access/audit?limit=501").getBody().get("code").asText());
    }

    @Test
    void theSealedCodeElevatesOneSessionForHalfAnHour() {
        Session root = signInAdmin();
        Session viewer = signInAsNewUser(root, "VIEWER");

        assertEquals(HttpStatus.FORBIDDEN, get(viewer, "/v1/ops/access/roles").getStatusCode());

        ResponseEntity<JsonNode> shortReason = breakGlass(viewer, "too short", EMERGENCY_CODE);
        assertEquals(HttpStatus.BAD_REQUEST, shortReason.getStatusCode());
        assertEquals("BREAK_GLASS_REASON_REQUIRED", shortReason.getBody().get("code").asText());

        ResponseEntity<JsonNode> wrongCode = breakGlass(viewer,
                "restore console access after the administrator account lockout",
                "not-the-emergency-code");
        assertEquals(HttpStatus.FORBIDDEN, wrongCode.getStatusCode());
        assertEquals("BREAK_GLASS_CODE_INVALID", wrongCode.getBody().get("code").asText());
        Integer refused = jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE action = 'BREAK_GLASS_STARTED'"
                        + " AND outcome = 'DENIED' AND actor_user_id = ?::uuid",
                Integer.class, viewer.body().get("userId").asText());
        assertEquals(1, refused);

        ResponseEntity<JsonNode> granted = breakGlass(viewer,
                "restore console access after the administrator account lockout",
                EMERGENCY_CODE);
        assertEquals(HttpStatus.CREATED, granted.getStatusCode(), String.valueOf(granted.getBody()));
        assertTrue(granted.getBody().get("active").asBoolean());
        assertFalse(granted.getBody().get("reviewDue").asBoolean());
        assertEquals("PENDING", granted.getBody().get("reviewStatus").asText());

        ResponseEntity<JsonNode> elevated = get(viewer, "/v1/ops/me");
        assertTrue(elevated.getBody().get("breakGlass").asBoolean());
        assertEquals("VIEWER", elevated.getBody().get("role").asText());
        assertTrue(elevated.getBody().get("permissions").toString().contains("MANAGE_USERS"));
        assertFalse(get(root, "/v1/ops/me").getBody().get("breakGlass").asBoolean());

        assertEquals(HttpStatus.OK, get(viewer, "/v1/ops/access/roles").getStatusCode());

        assertEquals(HttpStatus.CONFLICT,
                breakGlass(viewer, "a second grant while the first is still live",
                        EMERGENCY_CODE).getStatusCode());

        JsonNode ended = rest.exchange("/v1/ops/access/break-glass", HttpMethod.DELETE,
                new HttpEntity<>(sessionHeaders(viewer, viewer.csrf())), JsonNode.class).getBody();
        assertFalse(ended.get("active").asBoolean());
        assertNotNull(ended.get("endedAt"));
        assertEquals(HttpStatus.FORBIDDEN, get(viewer, "/v1/ops/access/roles").getStatusCode());
        assertFalse(get(viewer, "/v1/ops/me").getBody().get("breakGlass").asBoolean());
    }

    @Test
    void aGrantIsReviewedBySomeoneElseAndNeverByItsGrantee() {
        Session root = signInAdmin();
        Session second = signInAsNewAdmin(root);
        String reason = "recover the locked administrator without the usual path";

        String grantId = breakGlass(root, reason, EMERGENCY_CODE).getBody()
                .get("grantId").asText();

        ResponseEntity<JsonNode> stillActive = review(root, grantId,
                "tried to close the review while the grant was live");
        assertEquals(HttpStatus.CONFLICT, stillActive.getStatusCode());
        assertEquals("BREAK_GLASS_STILL_ACTIVE", stillActive.getBody().get("code").asText());

        rest.exchange("/v1/ops/access/break-glass", HttpMethod.DELETE,
                new HttpEntity<>(sessionHeaders(root, root.csrf())), JsonNode.class);

        ResponseEntity<JsonNode> self = review(root, grantId,
                "reviewing my own emergency elevation");
        assertEquals(HttpStatus.CONFLICT, self.getStatusCode());
        assertEquals("BREAK_GLASS_SELF_REVIEW", self.getBody().get("code").asText());
        Integer audited = jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE action = 'BREAK_GLASS_SELF_REVIEW'"
                        + " AND outcome = 'DENIED' AND actor_user_id = ?::uuid",
                Integer.class, root.body().get("userId").asText());
        assertEquals(1, audited);

        ResponseEntity<JsonNode> shortNote = review(second, grantId, "fine");
        assertEquals(HttpStatus.BAD_REQUEST, shortNote.getStatusCode());
        assertEquals("BREAK_GLASS_REVIEW_NOTE_REQUIRED", shortNote.getBody().get("code").asText());

        ResponseEntity<JsonNode> reviewed = review(second, grantId,
                "confirmed the lockout with the network team the next morning");
        assertEquals(HttpStatus.OK, reviewed.getStatusCode(), String.valueOf(reviewed.getBody()));
        assertEquals("REVIEWED", reviewed.getBody().get("reviewStatus").asText());
        assertEquals(second.body().get("username").asText(),
                reviewed.getBody().get("reviewedByName").asText());
        assertFalse(reviewed.getBody().get("reviewDue").asBoolean());

        assertEquals(HttpStatus.CONFLICT, review(second, grantId,
                "reviewing the same grant a second time").getStatusCode());

        JsonNode reviewedList = get(root, "/v1/ops/access/break-glass?status=REVIEWED").getBody();
        assertEquals(1, reviewedList.size());
        assertEquals(grantId, reviewedList.get(0).get("grantId").asText());
        assertEquals(0, get(root, "/v1/ops/access/break-glass?status=PENDING").getBody().size());
    }

    @Test
    void clientsAreListedWithTheirQuotasAndPurposesWithoutKeyMaterial() {
        Session root = signInAdmin();
        String name = "Access client " + UUID.randomUUID();
        UUID clientId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO api_client (client_id, tenant_id, name, key_hash, purposes,
                    max_accuracy_m, rate_per_second, rate_per_day, ip_allowlist)
                VALUES (?, 1, ?, ?, ARRAY['COMMERCIAL_LBS'], 250, 7, 700, ARRAY['198.51.100.0/24'])
                """, clientId, name, OpsSessionService.sha256(name));

        JsonNode rows = get(root, "/v1/ops/access/clients").getBody();
        JsonNode row = null;
        for (JsonNode candidate : rows) {
            if (name.equals(candidate.get("name").asText())) {
                row = candidate;
            }
        }
        assertNotNull(row);
        assertEquals("COMMERCIAL_LBS", row.get("purposes").get(0).asText());
        assertEquals(250, row.get("maxAccuracyM").asInt());
        assertEquals(7, row.get("ratePerSecond").asInt());
        assertEquals(700, row.get("ratePerDay").asInt());
        assertEquals("198.51.100.0/24", row.get("ipAllowlist").get(0).asText());
        assertTrue(row.path("keyHash").isMissingNode());
        assertFalse(row.get("ipAllowlist").toString().contains("key"));

        Session operator = signInAsNewUser(root, "OPERATOR");
        assertEquals(HttpStatus.FORBIDDEN,
                get(operator, "/v1/ops/access/clients").getStatusCode());
    }

    // ---------------------------------------------------------------- helpers

    private ResponseEntity<JsonNode> breakGlass(Session session, String reason, String code) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reason", reason);
        body.put("code", code);
        return rest.exchange("/v1/ops/access/break-glass", HttpMethod.POST,
                new HttpEntity<>(body, sessionHeaders(session, session.csrf())), JsonNode.class);
    }

    private ResponseEntity<JsonNode> review(Session session, String grantId, String note) {
        return rest.exchange("/v1/ops/access/break-glass/" + grantId + "/review", HttpMethod.POST,
                new HttpEntity<>(Map.of("note", note), sessionHeaders(session, session.csrf())),
                JsonNode.class);
    }

    private ResponseEntity<JsonNode> get(Session session, String path) {
        return rest.exchange(path, HttpMethod.GET,
                new HttpEntity<>(sessionHeaders(session, null)), JsonNode.class);
    }

    private static JsonNode role(JsonNode matrix, String name) {
        for (JsonNode role : matrix.get("roles")) {
            if (name.equals(role.get("role").asText())) {
                return role;
            }
        }
        return null;
    }

    private Session signInAdmin() {
        return signIn("root", ADMIN_PASSWORD, ADMIN_TOTP);
    }

    private Session signInAsNewAdmin(Session admin) {
        return signInAsNewUser(admin, "ADMIN");
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
        UUID userId = UUID.fromString(created.getBody().get("user").get("userId").asText());
        createdUsers.add(userId);
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
