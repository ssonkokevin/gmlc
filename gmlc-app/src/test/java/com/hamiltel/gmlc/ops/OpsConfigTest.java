package com.hamiltel.gmlc.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.hamiltel.gmlc.client.ApiClient;
import com.hamiltel.gmlc.consent.ConsentGrantRequest;
import com.hamiltel.gmlc.consent.ConsentService;
import com.hamiltel.gmlc.location.LocationEstimate;
import com.hamiltel.gmlc.location.LocationLocator;
import com.hamiltel.gmlc.support.EmbeddedPostgresSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Configuration changes are versioned, reasoned and audited. In a production
 * deployment a threshold applies immediately but a change to the name carried
 * on the signalling waits for a second administrator, who cannot be the person
 * who raised it. The applied values are read by the dashboard and the request
 * path, so the assertions are about behaviour, not about a stored row.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "gmlc.ops.cookie-secure=false",
        "gmlc.ops.argon2-memory-kib=4096",
        "gmlc.ops.argon2-iterations=1",
        "gmlc.ops.environment=PRODUCTION",
        "gmlc.ops.bootstrap-username=root",
        "gmlc.ops.bootstrap-password=" + OpsConfigTest.ADMIN_PASSWORD,
        "gmlc.ops.bootstrap-totp-secret=" + OpsConfigTest.ADMIN_TOTP,
        "gmlc.ops.alerts.enabled=false",
        "gmlc.webhooks.enabled=false",
        "gmlc.webhooks.initial-delay-seconds=3600",
        "gmlc.tracking.initial-delay-seconds=3600"
})
@Import(OpsConfigTest.CapturingLocator.class)
class OpsConfigTest {

    static final String ADMIN_PASSWORD = "bootstrap-password-123";
    static final String ADMIN_TOTP = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP";

    private static final String MSISDN = "256772000222";
    private static final String PURPOSE = "COMMERCIAL_LBS";

    @TestConfiguration
    static class CapturingLocator {

        @Bean
        @Primary
        CapturingLocationLocator capturingLocationLocator() {
            return new CapturingLocationLocator();
        }
    }

    static class CapturingLocationLocator implements LocationLocator {

        final AtomicReference<Query> lastQuery = new AtomicReference<>();

        @Override
        public Optional<LocationEstimate> locate(Query query) {
            lastQuery.set(query);
            return Optional.empty();
        }

        @Override
        public String protocol() {
            return "MAP";
        }
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ConsentService consents;

    @Autowired
    CapturingLocationLocator locator;

    private UUID clientId;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        EmbeddedPostgresSupport.register(registry);
    }

    @BeforeEach
    void reset() {
        rest.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
        jdbc.update("DELETE FROM config_change");
        jdbc.update("DELETE FROM config_item");
        locator.lastQuery.set(null);
        clientId = seedClient();
        consents.grant(new ApiClient(clientId, 1, "Config test client", Set.of(PURPOSE), 300, 5,
                        1000, List.of()),
                new ConsentGrantRequest(MSISDN, null, PURPOSE, "PORTAL",
                        Instant.now().plus(30, ChronoUnit.DAYS), null));
    }

    @AfterEach
    void cleanConfiguration() {
        // The embedded database is shared by every Spring context in the run,
        // so an applied override must not leak into another class' assertions.
        jdbc.update("DELETE FROM config_change");
        jdbc.update("DELETE FROM config_item");
    }

    @Test
    void aThresholdAppliesImmediatelyWithAVersionAndAReason() {
        Session root = signInAdmin();

        JsonNode before = get(root, "/v1/ops/configuration").getBody();
        assertEquals("PRODUCTION", before.get("environment").asText());
        assertTrue(before.get("secondApproverRequired").asBoolean());
        JsonNode sloItem = item(before, "alerts.sloP95Ms");
        assertNotNull(sloItem);
        assertEquals(0, sloItem.get("value").asInt());
        assertEquals("DEFAULT", sloItem.get("source").asText());

        ResponseEntity<JsonNode> applied = change(root, "alerts.sloP95Ms", 400,
                "agree a p95 objective with the network team");
        assertEquals(HttpStatus.CREATED, applied.getStatusCode(), String.valueOf(applied.getBody()));
        assertEquals("APPLIED", applied.getBody().get("status").asText());
        assertFalse(applied.getBody().get("requiresApproval").asBoolean());
        assertEquals(1, applied.getBody().get("appliedVersion").asInt());

        JsonNode after = get(root, "/v1/ops/configuration").getBody();
        JsonNode stored = item(after, "alerts.sloP95Ms");
        assertEquals(400, stored.get("value").asInt());
        assertEquals("DATABASE", stored.get("source").asText());
        assertEquals(1, stored.get("version").asInt());

        ResponseEntity<JsonNode> summary = get(root, "/v1/ops/summary?range=24h");
        assertEquals(HttpStatus.OK, summary.getStatusCode());
        assertEquals(400, summary.getBody().get("sloP95Ms").asInt());

        JsonNode history = get(root, "/v1/ops/configuration/changes").getBody();
        assertEquals(1, history.size());
        assertEquals("APPLIED", history.get(0).get("status").asText());
        assertEquals("agree a p95 objective with the network team",
                history.get(0).get("reason").asText());
        assertTrue(history.get(0).path("previousValue").isMissingNode());
        assertEquals(400, history.get(0).get("requestedValue").asInt());
    }

    @Test
    void aSignallingChangeWaitsForADifferentAdministratorAndTakesEffect() {
        Session root = signInAdmin();
        Session second = signInAsNewAdmin(root);

        ResponseEntity<JsonNode> pending = change(root, "lcs.clientName", "GMLC-OPS",
                "present the agreed operator name on SLg and MAP");
        assertEquals(HttpStatus.CREATED, pending.getStatusCode(), String.valueOf(pending.getBody()));
        assertEquals("PENDING", pending.getBody().get("status").asText());
        assertTrue(pending.getBody().get("requiresApproval").asBoolean());
        assertTrue(pending.getBody().path("appliedVersion").isMissingNode());

        JsonNode waiting = get(root, "/v1/ops/configuration").getBody();
        JsonNode item = item(waiting, "lcs.clientName");
        assertNotNull(item.get("pending"));
        assertEquals("GMLC-OPS", item.get("pending").get("requestedValue").asText());
        assertEquals("DEFAULT", item.get("source").asText());

        ResponseEntity<JsonNode> self = approve(root, pending.getBody().get("changeId").asText());
        assertEquals(HttpStatus.CONFLICT, self.getStatusCode());
        assertEquals("CONFIG_SELF_APPROVAL", self.getBody().get("code").asText());
        Integer refused = jdbc.queryForObject("SELECT count(*) FROM audit_event"
                        + " WHERE action = 'CONFIG_SELF_APPROVAL_REFUSED' AND outcome = 'DENIED'"
                        + " AND actor_user_id = ?::uuid", Integer.class,
                root.body().get("userId").asText());
        assertEquals(1, refused);

        ResponseEntity<JsonNode> approved = approve(second,
                pending.getBody().get("changeId").asText());
        assertEquals(HttpStatus.OK, approved.getStatusCode(), String.valueOf(approved.getBody()));
        assertEquals("APPLIED", approved.getBody().get("status").asText());
        assertEquals(1, approved.getBody().get("appliedVersion").asInt());
        assertEquals(second.body().get("userId").asText(),
                approved.getBody().get("decidedBy").asText());

        JsonNode applied = item(get(root, "/v1/ops/configuration").getBody(), "lcs.clientName");
        assertEquals("GMLC-OPS", applied.get("value").asText());

        ResponseEntity<JsonNode> located = submitLocation(root);
        assertTrue(located.getStatusCode().is2xxSuccessful(), String.valueOf(located.getBody()));
        assertEquals("GMLC-OPS", locator.lastQuery.get().lcsClientName());

        Integer audited = jdbc.queryForObject("SELECT count(*) FROM audit_event"
                        + " WHERE action = 'CONFIG_CHANGE_APPLIED' AND outcome = 'ALLOWED'"
                        + " AND actor_user_id = ?::uuid", Integer.class,
                second.body().get("userId").asText());
        assertEquals(1, audited);
    }

    @Test
    void onePendingChangePerItemAndAStaleBaseVersionBothConflict() {
        Session root = signInAdmin();
        assertEquals(HttpStatus.CREATED,
                change(root, "lcs.clientName", "GMLC-ONE", "first name under consideration")
                        .getStatusCode());
        ResponseEntity<JsonNode> second = change(root, "lcs.clientName", "GMLC-TWO",
                "second name under consideration");
        assertEquals(HttpStatus.CONFLICT, second.getStatusCode());
        assertEquals("CONFIG_CHANGE_PENDING", second.getBody().get("code").asText());

        // Another node moved the item after the request was raised.
        jdbc.update("""
                INSERT INTO config_item (tenant_id, item_key, value, version, updated_by)
                VALUES (1, 'lcs.clientName', '"GMLC-NODE2"', 4,
                    (SELECT user_id FROM ops_user WHERE username = 'root'))
                """);
        Session secondAdmin = signInAsNewAdmin(root);
        String changeId = jdbc.queryForObject(
                "SELECT change_id::text FROM config_change WHERE status = 'PENDING'",
                String.class);
        ResponseEntity<JsonNode> stale = approve(secondAdmin, changeId);
        assertEquals(HttpStatus.CONFLICT, stale.getStatusCode());
        assertEquals("CONFIG_VERSION_CONFLICT", stale.getBody().get("code").asText());
    }

    @Test
    void valuesAndReasonsAreValidated() {
        Session root = signInAdmin();

        ResponseEntity<JsonNode> shortReason = change(root, "alerts.windowMinutes", 30, "short");
        assertEquals(HttpStatus.BAD_REQUEST, shortReason.getStatusCode());
        assertEquals("REASON_TOO_SHORT", shortReason.getBody().get("code").asText());

        ResponseEntity<JsonNode> outOfRange = change(root, "alerts.windowMinutes", 2,
                "a window shorter than the schema floor");
        assertEquals(HttpStatus.BAD_REQUEST, outOfRange.getStatusCode());
        assertEquals("CONFIG_VALUE_RANGE", outOfRange.getBody().get("code").asText());

        ResponseEntity<JsonNode> wrongType = change(root, "alerts.windowMinutes", "thirty",
                "a number typed as text by mistake");
        assertEquals(HttpStatus.BAD_REQUEST, wrongType.getStatusCode());
        assertEquals("CONFIG_VALUE_TYPE", wrongType.getBody().get("code").asText());

        ResponseEntity<JsonNode> unknown = change(root, "signalling.pointCode", 1,
                "an item this deployment does not expose");
        assertEquals(HttpStatus.NOT_FOUND, unknown.getStatusCode());
    }

    @Test
    void anOperatorWithoutManageConfigIsRefused() {
        Session root = signInAdmin();
        Session operator = signInAsNewUser(root, "OPERATOR");

        ResponseEntity<JsonNode> refused = get(operator, "/v1/ops/configuration");
        assertEquals(HttpStatus.FORBIDDEN, refused.getStatusCode());
        assertEquals("PERMISSION_DENIED", refused.getBody().get("code").asText());
    }

    // ---------------------------------------------------------------- helpers

    private UUID seedClient() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO api_client (client_id, tenant_id, name, key_hash, purposes,"
                        + " max_accuracy_m, enabled) VALUES (?, 1, ?, ?, ARRAY[?], 300, TRUE)",
                id, "Config test " + id, hex64(id), PURPOSE);
        return id;
    }

    private ResponseEntity<JsonNode> submitLocation(Session session) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("clientId", clientId.toString());
        body.put("msisdn", MSISDN);
        body.put("purpose", PURPOSE);
        body.put("accuracyMeters", 300);
        return rest.exchange("/v1/ops/locations", HttpMethod.POST,
                new HttpEntity<>(body, sessionHeaders(session, session.csrf())), JsonNode.class);
    }

    private ResponseEntity<JsonNode> change(Session session, String key, Object value,
                                            String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("key", key);
        body.put("value", value);
        body.put("reason", reason);
        return rest.exchange("/v1/ops/configuration/changes", HttpMethod.POST,
                new HttpEntity<>(body, sessionHeaders(session, session.csrf())), JsonNode.class);
    }

    private ResponseEntity<JsonNode> approve(Session session, String changeId) {
        return rest.exchange("/v1/ops/configuration/changes/" + changeId + "/approve",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(), sessionHeaders(session, session.csrf())),
                JsonNode.class);
    }

    private ResponseEntity<JsonNode> get(Session session, String path) {
        return rest.exchange(path, HttpMethod.GET,
                new HttpEntity<>(sessionHeaders(session, null)), JsonNode.class);
    }

    private static JsonNode item(JsonNode snapshot, String key) {
        for (JsonNode item : snapshot.get("items")) {
            if (key.equals(item.get("key").asText())) {
                return item;
            }
        }
        return null;
    }

    private Session signInAdmin() {
        return signIn("root", ADMIN_PASSWORD, ADMIN_TOTP);
    }

    private Session signInAsNewAdmin(Session admin) {
        String username = "approver-" + UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("displayName", "Second approver");
        body.put("role", "ADMIN");
        ResponseEntity<JsonNode> created = rest.exchange("/v1/ops/users", HttpMethod.POST,
                new HttpEntity<>(body, sessionHeaders(admin, admin.csrf())), JsonNode.class);
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), String.valueOf(created.getBody()));
        return signIn(username, created.getBody().get("oneTimePassword").asText(),
                created.getBody().get("totpSecret").asText());
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
