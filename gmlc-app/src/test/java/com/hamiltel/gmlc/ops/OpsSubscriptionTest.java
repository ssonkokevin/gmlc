package com.hamiltel.gmlc.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.hamiltel.gmlc.client.ApiClient;
import com.hamiltel.gmlc.consent.ConsentGrantRequest;
import com.hamiltel.gmlc.consent.ConsentService;
import com.hamiltel.gmlc.location.LocationEstimate;
import com.hamiltel.gmlc.location.LocationLocator;
import com.hamiltel.gmlc.support.EmbeddedPostgresSupport;
import com.hamiltel.gmlc.webhook.WebhookDispatcher;
import com.hamiltel.gmlc.webhook.WebhookSignature;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
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

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Webhook subscriptions are tested against a real local HTTP receiver. The
 * signed body, the delivery record and the retry behaviour are all observed
 * end to end: a stored geofence event is enqueued, then the dispatcher sends
 * it, and the receiver verifies the HMAC exactly as an integrator would.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "gmlc.ops.cookie-secure=false",
        "gmlc.ops.argon2-memory-kib=4096",
        "gmlc.ops.argon2-iterations=1",
        "gmlc.ops.environment=LAB",
        "gmlc.ops.bootstrap-username=root",
        "gmlc.ops.bootstrap-password=" + OpsSubscriptionTest.ADMIN_PASSWORD,
        "gmlc.ops.bootstrap-totp-secret=" + OpsSubscriptionTest.ADMIN_TOTP,
        "gmlc.ops.alerts.enabled=false",
        "gmlc.webhooks.enabled=false",
        "gmlc.webhooks.initial-delay-seconds=3600"
})
@Import(OpsSubscriptionTest.StubLocator.class)
class OpsSubscriptionTest {

    static final String ADMIN_PASSWORD = "bootstrap-password-123";
    static final String ADMIN_TOTP = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP";

    private static final String MSISDN = "256772000333";
    private static final String PURPOSE = "COMMERCIAL_LBS";
    private static final double INSIDE_LAT = 0.3476;
    private static final double INSIDE_LON = 32.5825;
    private static final double OUTSIDE_LAT = 0.3776;

    @TestConfiguration
    static class StubLocator {

        static volatile double latitude = INSIDE_LAT;
        static volatile double longitude = INSIDE_LON;

        @Bean
        @Primary
        LocationLocator stubLocationLocator() {
            return query -> Optional.of(new LocationEstimate("LTE", "CELL_ID", "CIRCLE",
                    BigDecimal.valueOf(latitude), BigDecimal.valueOf(longitude),
                    100, 90, 90, 5, true, query.accuracyMeters(),
                    "UGA-001", "CELL_DB", "lmf-lab", Instant.now(),
                    List.of("SLH_RIR", "SLG_PLA")));
        }
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ConsentService consents;

    @Autowired
    WebhookDispatcher webhooks;

    private HttpServer server;
    private volatile int responseStatus;
    private final List<Received> received = Collections.synchronizedList(new ArrayList<>());
    private UUID client;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        EmbeddedPostgresSupport.register(registry);
    }

    @BeforeEach
    void setUp() throws IOException {
        rest.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
        jdbc.update("DELETE FROM webhook_delivery");
        jdbc.update("DELETE FROM event_subscription");
        jdbc.update("DELETE FROM geofence_event");
        jdbc.update("DELETE FROM geofence_arm");
        jdbc.update("DELETE FROM geofence");
        jdbc.update("DELETE FROM cdr");
        jdbc.update("DELETE FROM location_result");
        jdbc.update("DELETE FROM location_request");

        StubLocator.latitude = INSIDE_LAT;
        StubLocator.longitude = INSIDE_LON;
        received.clear();
        responseStatus = 200;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            received.add(new Received(exchange.getRequestHeaders(),
                    new String(body, StandardCharsets.UTF_8)));
            exchange.sendResponseHeaders(responseStatus, -1);
            exchange.close();
        });
        server.start();

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String name = "Webhook LBS " + suffix;
        client = seedClient(name, true);
        consents.grant(new ApiClient(client, 1, name, Set.of(PURPOSE), 300, 5, 1000, List.of()),
                new ConsentGrantRequest(MSISDN, null, PURPOSE, "PORTAL",
                        Instant.now().plus(30, ChronoUnit.DAYS), null));
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void creatingNeedsManageGeofencesAndValidatesTheTarget() {
        Session admin = signInAdmin();
        String url = hookUrl();

        int createdBefore = auditCount(admin, "WEBHOOK_SUBSCRIPTION_CREATED", "ALLOWED");
        ResponseEntity<JsonNode> created = create(admin, "Ops sink", url,
                List.of("ENTER", "LEAVE"), null, true);
        assertEquals(HttpStatus.CREATED, created.getStatusCode(),
                String.valueOf(created.getBody()));
        String secret = created.getBody().get("secret").asText();
        assertEquals(64, secret.length());
        assertTrue(secret.chars().allMatch(c -> Character.digit(c, 16) >= 0));
        assertEquals(createdBefore + 1,
                auditCount(admin, "WEBHOOK_SUBSCRIPTION_CREATED", "ALLOWED"));
        UUID subscriptionId = UUID.fromString(
                created.getBody().get("subscription").get("subscriptionId").asText());

        ResponseEntity<JsonNode> duplicate = create(admin, "Ops sink", url,
                List.of("ENTER"), null, true);
        assertEquals(HttpStatus.CONFLICT, duplicate.getStatusCode());
        assertEquals("SUBSCRIPTION_NAME_TAKEN", duplicate.getBody().get("code").asText());

        ResponseEntity<JsonNode> list = get(admin, "/v1/ops/subscriptions");
        assertEquals(HttpStatus.OK, list.getStatusCode(), String.valueOf(list.getBody()));
        assertEquals(1, list.getBody().size());
        assertFalse(list.getBody().get(0).has("secret"),
                "the signing secret is shown once and never read back");

        Session viewer = signInAsNewUser(admin, "VIEWER");
        ResponseEntity<JsonNode> refused = create(viewer, "Viewer sink", url,
                List.of("ENTER"), null, true);
        assertEquals(HttpStatus.FORBIDDEN, refused.getStatusCode());
        assertEquals("MANAGE_GEOFENCES", refused.getBody().get("permission").asText());
        assertEquals(1, auditCount(viewer, "OPS_FORBIDDEN", "DENIED"));

        ResponseEntity<JsonNode> badScheme = create(admin, "Ftp sink", "ftp://example.test/hook",
                List.of("ENTER"), null, true);
        assertEquals(HttpStatus.BAD_REQUEST, badScheme.getStatusCode());
        assertEquals("WEBHOOK_URL_INVALID", badScheme.getBody().get("code").asText());

        ResponseEntity<JsonNode> credentials = create(admin, "Cred sink",
                "http://user:pass@127.0.0.1/hook", List.of("ENTER"), null, true);
        assertEquals(HttpStatus.BAD_REQUEST, credentials.getStatusCode());

        ResponseEntity<JsonNode> badType = create(admin, "Jump sink", url,
                List.of("JUMP"), null, true);
        assertEquals(HttpStatus.BAD_REQUEST, badType.getStatusCode());
        assertEquals("WEBHOOK_EVENT_TYPE_NOT_SUPPORTED", badType.getBody().get("code").asText());

        ResponseEntity<JsonNode> unknownFence = create(admin, "Unknown fence sink", url,
                List.of("ENTER"), UUID.randomUUID(), true);
        assertEquals(HttpStatus.NOT_FOUND, unknownFence.getStatusCode());

        ResponseEntity<JsonNode> noCsrf = rest.exchange("/v1/ops/subscriptions", HttpMethod.POST,
                new HttpEntity<>(createBody("No csrf", url, List.of("ENTER"), null, true),
                        sessionHeaders(admin, null)), JsonNode.class);
        assertEquals(HttpStatus.FORBIDDEN, noCsrf.getStatusCode());
        assertEquals("CSRF_REQUIRED", noCsrf.getBody().get("code").asText());

        Session analyst = signInAsNewUser(admin, "ANALYST");
        ResponseEntity<JsonNode> deliveries = get(analyst, "/v1/ops/subscriptions/"
                + subscriptionId + "/deliveries");
        assertEquals(HttpStatus.OK, deliveries.getStatusCode());
        assertEquals(0, deliveries.getBody().size());
    }

    @Test
    void anEventIsDeliveredSignedAndOnlyOnce() throws Exception {
        Session admin = signInAdmin();
        UUID geofenceId = createFence(admin, "Signed fence");
        arm(admin, geofenceId);
        ResponseEntity<JsonNode> created = create(admin, "Signed sink", hookUrl(),
                List.of("ENTER"), null, true);
        String secret = created.getBody().get("secret").asText();
        UUID subscriptionId = UUID.fromString(
                created.getBody().get("subscription").get("subscriptionId").asText());

        submitLocation(admin);
        assertEquals(1, pendingDeliveries());
        assertEquals(0, received.size());
        assertTrue(dispatchesDue() >= 1);
        assertEquals(1, received.size());

        Received delivery = received.get(0);
        String timestamp = delivery.headers().getFirst(WebhookSignature.TIMESTAMP_HEADER);
        assertNotNull(timestamp);
        String signature = delivery.headers().getFirst(WebhookSignature.SIGNATURE_HEADER);
        assertTrue(WebhookSignature.verify(secret, Long.parseLong(timestamp), delivery.body(),
                signature), "the receiver must be able to verify the HMAC");
        assertEquals("ENTER", delivery.headers().getFirst("X-GMLC-Event"));

        JsonNode payload = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(delivery.body());
        assertEquals("ENTER", payload.get("event").get("type").asText());
        assertEquals(geofenceId.toString(), payload.get("event").get("geofence").get("id").asText());
        assertFalse(delivery.body().contains(MSISDN), "the body must not carry the MSISDN");
        assertTrue(payload.get("event").get("subject").get("label").asText().contains("*"));
        assertEquals(100, payload.get("event").get("position").get("uncertaintyM").asInt());

        ResponseEntity<JsonNode> log = get(admin,
                "/v1/ops/subscriptions/" + subscriptionId + "/deliveries");
        assertEquals(1, log.getBody().size());
        assertEquals("DELIVERED", log.getBody().get(0).get("status").asText());
        assertEquals(1, log.getBody().get(0).get("attempts").asInt());
        assertEquals(200, log.getBody().get(0).get("lastStatusCode").asInt());

        assertEquals(0, dispatchesDue());
        assertEquals(1, received.size());
        Integer rows = jdbc.queryForObject("SELECT count(*) FROM webhook_delivery"
                + " WHERE subscription_id = ?", Integer.class, subscriptionId);
        assertEquals(1, rows);

        submitLocation(admin);
        assertEquals(0, pendingDeliveries(), "a second position inside writes no new ENTER");
    }

    @Test
    void aFailingReceiverIsRetriedAndThenMarkedFailed() {
        Session admin = signInAdmin();
        UUID geofenceId = createFence(admin, "Retry fence");
        arm(admin, geofenceId);
        UUID subscriptionId = UUID.fromString(create(admin, "Broken sink", hookUrl(),
                List.of("ENTER"), null, true)
                .getBody().get("subscription").get("subscriptionId").asText());
        responseStatus = 500;

        submitLocation(admin);
        Instant base = Instant.now();
        for (int attempt = 1; attempt <= 5; attempt++) {
            webhooks.dispatchDue(base.plusSeconds(3600L * attempt));
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT status, attempts, last_status_code, last_error"
                            + " FROM webhook_delivery WHERE subscription_id = ?",
                    subscriptionId);
            assertEquals(attempt, ((Number) row.get("attempts")).intValue());
            assertEquals(500, ((Number) row.get("last_status_code")).intValue());
            assertEquals(attempt == 5 ? "FAILED" : "PENDING", row.get("status"));
        }
        assertEquals(5, received.size());
        assertEquals(0, webhooks.dispatchDue(base.plusSeconds(3600L * 24)));

        ResponseEntity<JsonNode> log = get(admin,
                "/v1/ops/subscriptions/" + subscriptionId + "/deliveries");
        assertEquals("FAILED", log.getBody().get(0).get("status").asText());
        assertEquals(5, log.getBody().get(0).get("attempts").asInt());

        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM geofence_event"
                + " WHERE geofence_id = ? AND event_type = 'ENTER'", Integer.class, geofenceId),
                "a webhook failure never removes the event that produced it");
    }

    @Test
    void filteringAndDisablingAreHonoured() {
        Session admin = signInAdmin();
        UUID insideFence = createFence(admin, "Filter fence");
        UUID otherFence = createFence(admin, "Other fence");
        arm(admin, insideFence);

        create(admin, "Leaving only", hookUrl(), List.of("LEAVE"), otherFence, true);
        ResponseEntity<JsonNode> disabled = create(admin, "Entering disabled", hookUrl(),
                List.of("ENTER"), insideFence, false);
        UUID disabledId = UUID.fromString(
                disabled.getBody().get("subscription").get("subscriptionId").asText());

        submitLocation(admin);
        assertEquals(1, eventCount(insideFence, "ENTER"));
        assertEquals(0, deliveryRows(), "a disabled subscription and a fence mismatch enqueue nothing");

        ResponseEntity<JsonNode> enabled = patch(admin, disabledId, Map.of("enabled", true));
        assertEquals(HttpStatus.OK, enabled.getStatusCode());
        assertTrue(enabled.getBody().get("enabled").asBoolean());

        StubLocator.latitude = OUTSIDE_LAT;
        submitLocation(admin);
        assertEquals(1, eventCount(insideFence, "LEAVE"));
        assertEquals(0, deliveryRows(), "a LEAVE event does not match an ENTER subscription");

        StubLocator.latitude = INSIDE_LAT;
        submitLocation(admin);
        assertEquals(2, eventCount(insideFence, "ENTER"));
        assertEquals(1, deliveryRows(), "only the enabled ENTER subscription is enqueued");
    }

    // ---------------------------------------------------------------- helpers

    private Map<String, Object> createBody(String name, String url, List<String> eventTypes,
                                           UUID geofenceId, boolean enabled) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("url", url);
        body.put("eventTypes", eventTypes);
        if (geofenceId != null) {
            body.put("geofenceId", geofenceId.toString());
        }
        body.put("enabled", enabled);
        return body;
    }

    private ResponseEntity<JsonNode> create(Session session, String name, String url,
                                            List<String> eventTypes, UUID geofenceId,
                                            boolean enabled) {
        return rest.exchange("/v1/ops/subscriptions", HttpMethod.POST,
                new HttpEntity<>(createBody(name, url, eventTypes, geofenceId, enabled),
                        sessionHeaders(session, session.csrf())), JsonNode.class);
    }

    private ResponseEntity<JsonNode> patch(Session session, UUID subscriptionId,
                                           Map<String, Object> body) {
        return rest.exchange("/v1/ops/subscriptions/" + subscriptionId, HttpMethod.PATCH,
                new HttpEntity<>(body, sessionHeaders(session, session.csrf())), JsonNode.class);
    }

    private String hookUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
    }

    private int dispatchesDue() {
        return webhooks.dispatchDue(Instant.now().plusSeconds(30));
    }

    private int pendingDeliveries() {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM webhook_delivery"
                + " WHERE status = 'PENDING'", Integer.class);
        return count == null ? 0 : count;
    }

    private int deliveryRows() {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM webhook_delivery",
                Integer.class);
        return count == null ? 0 : count;
    }

    private UUID seedClient(String name, boolean enabled) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO api_client (client_id, tenant_id, name, key_hash, purposes,"
                        + " max_accuracy_m, enabled) VALUES (?, 1, ?, ?, ARRAY[?], 300, ?)",
                id, name, hex64(id), PURPOSE, enabled);
        return id;
    }

    private UUID createFence(Session session, String name) {
        Map<String, Object> geometry = new LinkedHashMap<>();
        geometry.put("shape", "CIRCLE");
        geometry.put("latitude", INSIDE_LAT);
        geometry.put("longitude", INSIDE_LON);
        geometry.put("radiusM", 1000);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("reportInside", false);
        body.put("geometry", geometry);
        ResponseEntity<JsonNode> created = rest.exchange("/v1/ops/geofences", HttpMethod.POST,
                new HttpEntity<>(body, sessionHeaders(session, session.csrf())), JsonNode.class);
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), String.valueOf(created.getBody()));
        return UUID.fromString(created.getBody().get("geofenceId").asText());
    }

    private void arm(Session session, UUID geofenceId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("clientId", client.toString());
        body.put("msisdn", MSISDN);
        body.put("purpose", PURPOSE);
        ResponseEntity<JsonNode> armed = rest.exchange("/v1/ops/geofences/" + geofenceId + "/arm",
                HttpMethod.POST, new HttpEntity<>(body, sessionHeaders(session, session.csrf())),
                JsonNode.class);
        assertEquals(HttpStatus.CREATED, armed.getStatusCode(), String.valueOf(armed.getBody()));
    }

    private void submitLocation(Session session) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("clientId", client.toString());
        body.put("msisdn", MSISDN);
        body.put("purpose", PURPOSE);
        body.put("accuracyMeters", 300);
        ResponseEntity<JsonNode> response = rest.exchange("/v1/ops/locations", HttpMethod.POST,
                new HttpEntity<>(body, sessionHeaders(session, session.csrf())), JsonNode.class);
        assertTrue(response.getStatusCode() == HttpStatus.ACCEPTED
                        || response.getStatusCode() == HttpStatus.OK,
                String.valueOf(response.getBody()));
    }

    private int eventCount(UUID geofenceId, String type) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM geofence_event"
                + " WHERE geofence_id = ? AND event_type = ?", Integer.class, geofenceId, type);
        return count == null ? 0 : count;
    }

    private int auditCount(Session session, String action, String outcome) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM audit_event"
                        + " WHERE action = ? AND outcome = ? AND actor_user_id = ?::uuid",
                Integer.class, action, outcome, session.body().get("userId").asText());
        return count == null ? 0 : count;
    }

    private ResponseEntity<JsonNode> get(Session session, String path) {
        return rest.exchange(path, HttpMethod.GET,
                new HttpEntity<>(sessionHeaders(session, null)), JsonNode.class);
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

    private record Received(Headers headers, String body) {
    }

    private record Session(String token, String csrf, JsonNode body) {
    }
}
