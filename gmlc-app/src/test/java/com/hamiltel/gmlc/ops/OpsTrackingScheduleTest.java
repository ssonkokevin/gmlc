package com.hamiltel.gmlc.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.hamiltel.gmlc.client.ApiClient;
import com.hamiltel.gmlc.consent.ConsentGrantRequest;
import com.hamiltel.gmlc.consent.ConsentService;
import com.hamiltel.gmlc.location.LocationEstimate;
import com.hamiltel.gmlc.location.LocationLocator;
import com.hamiltel.gmlc.support.EmbeddedPostgresSupport;
import com.hamiltel.gmlc.tracking.TrackingScheduleService;
import com.hamiltel.gmlc.webhook.WebhookSignature;
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

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 * Schedules place real governed requests on a timer, and a channel notifies
 * with a signed body. The test drives the timer explicitly so the assertions
 * are about the run the real path wrote, not about wall-clock waiting.
 *
 * <p>The signing secret is sealed with a deployment key, never returned after
 * creation and never logged; the receiver verifies the same signature a
 * geofence webhook uses.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "gmlc.ops.cookie-secure=false",
        "gmlc.ops.argon2-memory-kib=4096",
        "gmlc.ops.argon2-iterations=1",
        "gmlc.ops.environment=LAB",
        "gmlc.ops.bootstrap-username=root",
        "gmlc.ops.bootstrap-password=" + OpsTrackingScheduleTest.ADMIN_PASSWORD,
        "gmlc.ops.bootstrap-totp-secret=" + OpsTrackingScheduleTest.ADMIN_TOTP,
        "gmlc.ops.alerts.enabled=false",
        "gmlc.webhooks.enabled=false",
        "gmlc.webhooks.initial-delay-seconds=3600",
        "gmlc.tracking.initial-delay-seconds=3600",
        "gmlc.tracking.notifier-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
@Import(OpsTrackingScheduleTest.StubLocator.class)
class OpsTrackingScheduleTest {

    static final String ADMIN_PASSWORD = "bootstrap-password-123";
    static final String ADMIN_TOTP = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP";

    private static final String MSISDN = "256772000555";
    private static final String PURPOSE = "SAFETY";
    private static final double LAT = 0.3476;
    private static final double LON = 32.5825;

    @TestConfiguration
    static class StubLocator {

        @Bean
        @Primary
        LocationLocator stubLocationLocator() {
            return query -> Optional.of(new LocationEstimate("LTE", "CELL_ID", "CIRCLE",
                    BigDecimal.valueOf(LAT), BigDecimal.valueOf(LON),
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
    TrackingScheduleService schedules;

    private HttpServer server;
    private volatile String lastBody;
    private volatile String lastSignature;
    private volatile String lastTimestamp;
    private volatile String lastDelivery;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        EmbeddedPostgresSupport.register(registry);
    }

    @BeforeEach
    void reset() throws Exception {
        rest.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
        jdbc.update("DELETE FROM tracking_run");
        jdbc.update("DELETE FROM tracking_schedule");
        jdbc.update("DELETE FROM notification_channel");
        jdbc.update("DELETE FROM tracked_subject");
        jdbc.update("DELETE FROM cdr");
        jdbc.update("DELETE FROM location_result");
        jdbc.update("DELETE FROM location_request");
        lastBody = null;
        lastSignature = null;
        lastTimestamp = null;
        lastDelivery = null;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            lastBody = new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8);
            lastTimestamp = exchange.getRequestHeaders()
                    .getFirst(WebhookSignature.TIMESTAMP_HEADER);
            lastSignature = exchange.getRequestHeaders()
                    .getFirst(WebhookSignature.SIGNATURE_HEADER);
            lastDelivery = exchange.getRequestHeaders().getFirst("X-GMLC-Delivery");
            byte[] accepted = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, accepted.length);
            exchange.getResponseBody().write(accepted);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void channelSecretsAreSealedAndNeverReturnedAfterCreation() {
        Session admin = signInAdmin();
        String url = endpoint();

        ResponseEntity<JsonNode> created = createChannel(admin, "ops-hook", "WEBHOOK", url);
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), String.valueOf(created.getBody()));
        String secret = created.getBody().get("secret").asText();
        assertNotNull(secret);
        assertEquals(64, secret.length());
        assertNotNull(created.getBody().get("secretNotice"));
        UUID channelId = UUID.fromString(created.getBody().get("channel").get("channelId").asText());

        String sealed = jdbc.queryForObject("SELECT secret_sealed FROM notification_channel"
                + " WHERE channel_id = ?", String.class, channelId);
        assertTrue(sealed.startsWith("v1:"));
        assertFalse(sealed.contains(secret));

        ResponseEntity<JsonNode> list = get(admin, "/v1/ops/tracking/channels");
        assertEquals(HttpStatus.OK, list.getStatusCode());
        assertEquals(1, list.getBody().size());
        assertFalse(list.getBody().toString().contains(secret));
        assertTrue(list.getBody().get(0).get("secret") == null
                || list.getBody().get(0).get("secret").isNull());
        assertEquals(url, list.getBody().get(0).get("url").asText());

        ResponseEntity<JsonNode> duplicate = createChannel(admin, "OPS-HOOK", "WEBHOOK", url);
        assertEquals(HttpStatus.CONFLICT, duplicate.getStatusCode());
        assertEquals("CHANNEL_ALREADY_EXISTS", duplicate.getBody().get("code").asText());

        ResponseEntity<JsonNode> email = createChannel(admin, "mail", "EMAIL", url);
        assertEquals(HttpStatus.BAD_REQUEST, email.getStatusCode());
        assertEquals("NOTIFIER_NOT_AVAILABLE", email.getBody().get("code").asText());

        ResponseEntity<JsonNode> fileUrl = createChannel(admin, "file", "WEBHOOK",
                "file:///etc/passwd");
        assertEquals(HttpStatus.BAD_REQUEST, fileUrl.getStatusCode());
        assertEquals("NOTIFIER_URL_INVALID", fileUrl.getBody().get("code").asText());

        Session analyst = signInAsNewUser(admin, "ANALYST");
        ResponseEntity<JsonNode> refused = createChannel(analyst, "analyst-hook", "WEBHOOK", url);
        assertEquals(HttpStatus.FORBIDDEN, refused.getStatusCode());
        assertEquals("MANAGE_NOTIFIERS", refused.getBody().get("permission").asText());

        Session viewer = signInAsNewUser(admin, "VIEWER");
        assertEquals(HttpStatus.OK, get(viewer, "/v1/ops/tracking/channels").getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN,
                createChannel(viewer, "viewer-hook", "WEBHOOK", url).getStatusCode());

        assertEquals(HttpStatus.NO_CONTENT,
                delete(admin, "/v1/ops/tracking/channels/" + channelId).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND,
                get(admin, "/v1/ops/tracking/channels/" + channelId).getStatusCode());
    }

    @Test
    void schedulesEnforceTheFloorWindowAndChannelRules() {
        Session admin = signInAdmin();
        Client owner = registerClient("schedule-owner", PURPOSE);
        grantConsent(owner, MSISDN);
        UUID trackedId = addSubject(admin, owner);

        ResponseEntity<JsonNode> shortInterval = createSchedule(admin, Map.of(
                "trackedId", trackedId.toString(), "intervalSeconds", 60));
        assertEquals(HttpStatus.BAD_REQUEST, shortInterval.getStatusCode());
        assertEquals("INTERVAL_TOO_SHORT", shortInterval.getBody().get("code").asText());

        ResponseEntity<JsonNode> tooAccurate = createSchedule(admin, Map.of(
                "trackedId", trackedId.toString(), "intervalSeconds", 300,
                "accuracyMeters", 100));
        assertEquals(HttpStatus.BAD_REQUEST, tooAccurate.getStatusCode(), String.valueOf(tooAccurate.getBody()));
        assertEquals("ACCURACY_NOT_ALLOWED", tooAccurate.getBody().get("code").asText());

        ResponseEntity<JsonNode> noChannel = createSchedule(admin, Map.of(
                "trackedId", trackedId.toString(), "intervalSeconds", 300,
                "notifyOnFix", true));
        assertEquals(HttpStatus.BAD_REQUEST, noChannel.getStatusCode());
        assertEquals("NOTIFY_CHANNEL_REQUIRED", noChannel.getBody().get("code").asText());

        ResponseEntity<JsonNode> unusedChannel = createSchedule(admin, Map.of(
                "trackedId", trackedId.toString(), "intervalSeconds", 300,
                "notifyOnFix", false, "channelId", UUID.randomUUID().toString()));
        assertEquals(HttpStatus.BAD_REQUEST, unusedChannel.getStatusCode());
        assertEquals("NOTIFY_CHANNEL_UNUSED", unusedChannel.getBody().get("code").asText());

        int allowedBefore = auditCount(admin, "TRACKING_SCHEDULE_ADDED", "ALLOWED");
        ResponseEntity<JsonNode> created = createSchedule(admin, Map.of(
                "trackedId", trackedId.toString(), "intervalSeconds", 300));
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), String.valueOf(created.getBody()));
        assertEquals(300, created.getBody().get("intervalSeconds").asInt());
        assertTrue(created.getBody().get("notifyOnFix") == null
                || !created.getBody().get("notifyOnFix").asBoolean());
        assertEquals(allowedBefore + 1, auditCount(admin, "TRACKING_SCHEDULE_ADDED", "ALLOWED"));
        UUID scheduleId = UUID.fromString(created.getBody().get("scheduleId").asText());

        ResponseEntity<JsonNode> duplicate = createSchedule(admin, Map.of(
                "trackedId", trackedId.toString(), "intervalSeconds", 600));
        assertEquals(HttpStatus.CONFLICT, duplicate.getStatusCode());
        assertEquals("SCHEDULE_ALREADY_EXISTS", duplicate.getBody().get("code").asText());

        ResponseEntity<JsonNode> badWindow = createSchedule(admin, Map.of(
                "trackedId", trackedId.toString(), "intervalSeconds", 300,
                "startsAt", Instant.now().plus(1, ChronoUnit.HOURS).toString(),
                "endsAt", Instant.now().plus(2, ChronoUnit.MINUTES).toString()));
        assertEquals(HttpStatus.BAD_REQUEST, badWindow.getStatusCode());
        assertEquals("SCHEDULE_WINDOW_INVALID", badWindow.getBody().get("code").asText());

        Client second = registerClient("schedule-paused", PURPOSE);
        grantConsent(second, MSISDN);
        UUID pausedTracked = addSubject(admin, second);
        assertEquals(HttpStatus.OK, patch(admin, "/v1/ops/tracking/" + pausedTracked,
                Map.of("enabled", false)).getStatusCode());
        ResponseEntity<JsonNode> paused = createSchedule(admin, Map.of(
                "trackedId", pausedTracked.toString(), "intervalSeconds", 300));
        assertEquals(HttpStatus.BAD_REQUEST, paused.getStatusCode());
        assertEquals("TRACKING_DISABLED", paused.getBody().get("code").asText());

        Client rescind = registerClient("schedule-rescind", PURPOSE);
        grantConsent(rescind, MSISDN);
        UUID thirdTracked = addSubject(admin, rescind);
        revokeConsent(rescind, MSISDN);
        ResponseEntity<JsonNode> denied = createSchedule(admin, Map.of(
                "trackedId", thirdTracked.toString(), "intervalSeconds", 300));
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode());
        assertEquals("PRIVACY_NOT_SATISFIED", denied.getBody().get("code").asText());
        assertEquals(1, auditCount(admin, "TRACKING_SCHEDULE_ADDED", "DENIED"));

        Session viewer = signInAsNewUser(admin, "VIEWER");
        assertEquals(HttpStatus.OK, get(viewer, "/v1/ops/tracking/schedules").getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, createSchedule(viewer, Map.of(
                "trackedId", trackedId.toString(), "intervalSeconds", 300)).getStatusCode());

        assertEquals(HttpStatus.NO_CONTENT,
                delete(admin, "/v1/ops/tracking/schedules/" + scheduleId).getStatusCode());
        assertEquals(HttpStatus.OK, get(admin, "/v1/ops/tracking/schedules").getStatusCode());
        assertEquals(0, get(admin, "/v1/ops/tracking/schedules").getBody().size());
    }

    @Test
    void aRunPlacesARequestAndSendsASignedFix() {
        Session admin = signInAdmin();
        Client owner = registerClient("run-owner", PURPOSE);
        grantConsent(owner, MSISDN);
        UUID trackedId = addSubject(admin, owner);
        Response channel = createChannelResponse(admin, "run-hook", endpoint());

        ResponseEntity<JsonNode> created = createSchedule(admin, Map.of(
                "trackedId", trackedId.toString(), "intervalSeconds", 300,
                "startsAt", Instant.now().minus(10, ChronoUnit.MINUTES).toString(),
                "notifyOnFix", true, "channelId", channel.channelId().toString()));
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), String.valueOf(created.getBody()));
        assertEquals(channel.channelId().toString(), created.getBody().get("channelId").asText());

        assertEquals(1, schedules.dispatchDue(Instant.now()));

        ResponseEntity<JsonNode> runs = get(admin,
                "/v1/ops/tracking/schedules/" + created.getBody().get("scheduleId").asText()
                        + "/runs");
        assertEquals(HttpStatus.OK, runs.getStatusCode(), String.valueOf(runs.getBody()));
        assertEquals(1, runs.getBody().size());
        JsonNode run = runs.getBody().get(0);
        assertEquals("SCHEDULE", run.get("trigger").asText());
        assertEquals("LOCATED", run.get("state").asText());
        assertEquals("SENT", run.get("notifyState").asText());
        String requestId = run.get("requestId").asText();
        assertNotNull(requestId);

        Integer requests = jdbc.queryForObject("SELECT count(*) FROM location_request"
                + " WHERE request_id = ?::uuid AND purpose = ?", Integer.class,
                requestId, PURPOSE);
        assertEquals(1, requests);
        assertFalse(run.toString().contains(MSISDN));

        assertNotNull(lastBody, "the receiver saw a notification");
        JsonNode body = read(lastBody);
        assertEquals("tracking.fix", body.get("event").get("type").asText());
        assertEquals(trackedId.toString(), body.get("event").get("trackedId").asText());
        assertEquals(requestId, body.get("event").get("requestId").asText());
        assertEquals(100, body.get("event").get("position").get("uncertaintyM").asInt());
        assertEquals(90, body.get("event").get("position").get("confidence").asInt());
        assertEquals(5, body.get("event").get("position").get("ageSeconds").asInt());
        assertEquals("CELL_ID", body.get("event").get("position").get("method").asText());
        assertTrue(body.get("event").get("subject").get("label").asText().startsWith("25677"));
        assertFalse(lastBody.contains(MSISDN));
        assertNotNull(lastDelivery);
        assertTrue(WebhookSignature.verify(channel.secret(),
                Long.parseLong(lastTimestamp), lastBody, lastSignature));

        JsonNode scheduleAfter = get(admin, "/v1/ops/tracking/schedules/"
                + created.getBody().get("scheduleId").asText()).getBody();
        assertEquals("LOCATED", scheduleAfter.get("lastState").asText());
        assertTrue(Instant.parse(scheduleAfter.get("nextRunAt").asText())
                .isAfter(Instant.now().plus(200, ChronoUnit.SECONDS)));

        assertEquals(0, schedules.dispatchDue(Instant.now()), "the next run is an interval away");

        ResponseEntity<JsonNode> known = rest.exchange(
                "/v1/subscribers/" + trackedId + "/last-known", HttpMethod.GET,
                new HttpEntity<>(EmbeddedPostgresSupport.bearer(owner.apiKey())), JsonNode.class);
        assertTrue(known.getBody().get("found").asBoolean());
        assertEquals(100, known.getBody().get("fix").get("uncertaintyM").asInt());

        JsonNode summary = get(admin, "/v1/ops/tracking/summary").getBody();
        assertEquals(1, summary.get("schedules").asLong());
        assertEquals(1, summary.get("runs").asLong());
        assertEquals(1, summary.get("channels").asLong());
        assertTrue(summary.get("notifierConfigured").asBoolean());

        ResponseEntity<JsonNode> inUse = delete(admin,
                "/v1/ops/tracking/channels/" + channel.channelId());
        assertEquals(HttpStatus.CONFLICT, inUse.getStatusCode());
        assertEquals("CHANNEL_IN_USE", inUse.getBody().get("code").asText());
    }

    @Test
    void aManualRunIsRecordedAndConsentLossFailsTheNextOne() {
        Session admin = signInAdmin();
        Client owner = registerClient("manual-owner", PURPOSE);
        grantConsent(owner, MSISDN);
        UUID trackedId = addSubject(admin, owner);
        ResponseEntity<JsonNode> created = createSchedule(admin, Map.of(
                "trackedId", trackedId.toString(), "intervalSeconds", 300));
        String scheduleId = created.getBody().get("scheduleId").asText();

        ResponseEntity<JsonNode> manual = post(admin,
                "/v1/ops/tracking/schedules/" + scheduleId + "/run", Map.of());
        assertEquals(HttpStatus.OK, manual.getStatusCode(), String.valueOf(manual.getBody()));
        assertEquals("MANUAL", manual.getBody().get("trigger").asText());
        assertEquals("LOCATED", manual.getBody().get("state").asText());

        revokeConsent(owner, MSISDN);
        ResponseEntity<JsonNode> denied = post(admin,
                "/v1/ops/tracking/schedules/" + scheduleId + "/run", Map.of());
        assertEquals(HttpStatus.OK, denied.getStatusCode(), String.valueOf(denied.getBody()));
        assertEquals("FAILED", denied.getBody().get("state").asText());
        assertEquals("PRIVACY_NOT_SATISFIED", denied.getBody().get("errorCode").asText());
        assertEquals("NONE", denied.getBody().get("notifyState").asText());

        patch(admin, "/v1/ops/tracking/schedules/" + scheduleId, Map.of("enabled", false));
        jdbc.update("UPDATE tracking_schedule SET next_run_at = now() - interval '1 hour'"
                + " WHERE schedule_id = ?::uuid", scheduleId);
        assertEquals(0, schedules.dispatchDue(Instant.now()), "a paused schedule does not run");

        patch(admin, "/v1/ops/tracking/schedules/" + scheduleId, Map.of("enabled", true));
        ResponseEntity<JsonNode> enabled = get(admin,
                "/v1/ops/tracking/schedules/" + scheduleId);
        assertTrue(enabled.getBody().get("enabled").asBoolean());
        assertEquals(1, schedules.dispatchDue(Instant.now()));

        ResponseEntity<JsonNode> runs = get(admin,
                "/v1/ops/tracking/schedules/" + scheduleId + "/runs");
        assertEquals(3, runs.getBody().size());

        assertEquals(HttpStatus.NO_CONTENT,
                delete(admin, "/v1/ops/tracking/schedules/" + scheduleId).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, get(admin,
                "/v1/ops/tracking/schedules/" + scheduleId).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, get(admin,
                "/v1/ops/tracking/schedules/" + scheduleId + "/runs").getStatusCode());
    }

    // ---------------------------------------------------------------- helpers

    private String endpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
    }

    private JsonNode read(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        } catch (java.io.IOException e) {
            throw new AssertionError("the notification body is not JSON", e);
        }
    }

    private UUID addSubject(Session admin, Client client) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("clientId", client.id().toString());
        body.put("msisdn", MSISDN);
        body.put("purpose", PURPOSE);
        ResponseEntity<JsonNode> response = post(admin, "/v1/ops/tracking", body);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(), String.valueOf(response.getBody()));
        assertFalse(response.getBody().toString().contains(MSISDN));
        return UUID.fromString(response.getBody().get("trackedId").asText());
    }

    private ResponseEntity<JsonNode> createSchedule(Session session, Map<String, Object> body) {
        return post(session, "/v1/ops/tracking/schedules", body);
    }

    private ResponseEntity<JsonNode> createChannel(Session session, String name, String type,
                                                   String url) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("type", type);
        body.put("url", url);
        return post(session, "/v1/ops/tracking/channels", body);
    }

    private Response createChannelResponse(Session session, String name, String url) {
        ResponseEntity<JsonNode> response = createChannel(session, name, "WEBHOOK", url);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(), String.valueOf(response.getBody()));
        return new Response(UUID.fromString(response.getBody().get("channel")
                .get("channelId").asText()), response.getBody().get("secret").asText());
    }

    private Client registerClient(String prefix, String purpose) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", prefix + "-" + UUID.randomUUID().toString().substring(0, 8));
        body.put("purposes", List.of(purpose));
        body.put("maxAccuracyM", 300);
        ResponseEntity<JsonNode> response = rest.exchange("/v1/admin/clients", HttpMethod.POST,
                new HttpEntity<>(body, EmbeddedPostgresSupport.adminHeaders()), JsonNode.class);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(), String.valueOf(response.getBody()));
        return new Client(UUID.fromString(response.getBody().get("clientId").asText()),
                response.getBody().get("apiKey").asText());
    }

    private void grantConsent(Client client, String msisdn) {
        ApiClient apiClient = new ApiClient(client.id(), 1, "test", Set.of(PURPOSE),
                300, 5, 1000, List.of());
        consents.grant(apiClient, new ConsentGrantRequest(msisdn, null, PURPOSE, "PORTAL",
                Instant.now().plus(30, ChronoUnit.DAYS), null));
    }

    private void revokeConsent(Client client, String msisdn) {
        UUID consentId = jdbc.queryForObject("SELECT consent_id FROM consent"
                        + " WHERE client_id = ? AND purpose = ? AND status = 'GRANTED'"
                        + " ORDER BY granted_at DESC LIMIT 1",
                UUID.class, client.id(), PURPOSE);
        assertNotNull(consentId);
        jdbc.update("UPDATE consent SET status = 'REVOKED', revoked_at = now()"
                + " WHERE consent_id = ?", consentId);
    }

    private ResponseEntity<JsonNode> get(Session session, String path) {
        return rest.exchange(path, HttpMethod.GET,
                new HttpEntity<>(sessionHeaders(session, null)), JsonNode.class);
    }

    private ResponseEntity<JsonNode> post(Session session, String path, Map<String, Object> body) {
        return rest.exchange(path, HttpMethod.POST,
                new HttpEntity<>(body, sessionHeaders(session, session.csrf())), JsonNode.class);
    }

    private ResponseEntity<JsonNode> patch(Session session, String path, Map<String, Object> body) {
        return rest.exchange(path, HttpMethod.PATCH,
                new HttpEntity<>(body, sessionHeaders(session, session.csrf())), JsonNode.class);
    }

    private ResponseEntity<JsonNode> delete(Session session, String path) {
        return rest.exchange(path, HttpMethod.DELETE,
                new HttpEntity<>(sessionHeaders(session, session.csrf())), JsonNode.class);
    }

    private int auditCount(Session session, String action, String outcome) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM audit_event"
                        + " WHERE action = ? AND outcome = ? AND actor_user_id = ?::uuid",
                Integer.class, action, outcome, session.body().get("userId").asText());
        return count == null ? 0 : count;
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
        ResponseEntity<JsonNode> created = post(admin, "/v1/ops/users", body);
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

    private record Session(String token, String csrf, JsonNode body) {
    }

    private record Client(UUID id, String apiKey) {
    }

    private record Response(UUID channelId, String secret) {
    }
}
