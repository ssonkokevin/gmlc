package com.hamiltel.gmlc.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.hamiltel.gmlc.client.ApiClient;
import com.hamiltel.gmlc.consent.ConsentGrantRequest;
import com.hamiltel.gmlc.consent.ConsentService;
import com.hamiltel.gmlc.location.LocationEstimate;
import com.hamiltel.gmlc.location.LocationLocator;
import com.hamiltel.gmlc.support.EmbeddedPostgresSupport;
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
 * A geofence is a definition plus arms; its events come from results the
 * gateway actually stored. The locator is stubbed so the tests can place the
 * subject inside and then outside a circle and check the transition the real
 * request path wrote, not a simulated one.
 *
 * <p>Arming names a client and purpose and needs consent. Events keep only
 * the masked label; the MSISDN never appears in the feed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "gmlc.ops.cookie-secure=false",
        "gmlc.ops.argon2-memory-kib=4096",
        "gmlc.ops.argon2-iterations=1",
        "gmlc.ops.environment=LAB",
        "gmlc.ops.bootstrap-username=root",
        "gmlc.ops.bootstrap-password=" + OpsGeofenceTest.ADMIN_PASSWORD,
        "gmlc.ops.bootstrap-totp-secret=" + OpsGeofenceTest.ADMIN_TOTP,
        "gmlc.ops.alerts.enabled=false"
})
@Import(OpsGeofenceTest.StubLocator.class)
class OpsGeofenceTest {

    static final String ADMIN_PASSWORD = "bootstrap-password-123";
    static final String ADMIN_TOTP = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP";

    private static final String MSISDN = "256772000222";
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

    private UUID consentedClient;
    private UUID unconsentedClient;
    private String consentedName;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        EmbeddedPostgresSupport.register(registry);
    }

    @BeforeEach
    void seed() {
        rest.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
        jdbc.update("DELETE FROM geofence_event");
        jdbc.update("DELETE FROM geofence_arm");
        jdbc.update("DELETE FROM geofence");
        jdbc.update("DELETE FROM cdr");
        jdbc.update("DELETE FROM location_result");
        jdbc.update("DELETE FROM location_request");

        StubLocator.latitude = INSIDE_LAT;
        StubLocator.longitude = INSIDE_LON;

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        consentedName = "Geofence LBS " + suffix;
        consentedClient = seedClient(consentedName, true);
        unconsentedClient = seedClient("Geofence No Consent " + suffix, true);

        ApiClient client = new ApiClient(consentedClient, 1, consentedName, Set.of(PURPOSE),
                300, 5, 1000, List.of());
        consents.grant(client, new ConsentGrantRequest(MSISDN, null, PURPOSE, "PORTAL",
                Instant.now().plus(30, ChronoUnit.DAYS), null));
    }

    @Test
    void creatingNeedsManageGeofencesAndAValidTs23032Shape() {
        Session admin = signInAdmin();

        ResponseEntity<JsonNode> created = create(admin, "Kampala depot", circle(1000), false);
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), String.valueOf(created.getBody()));
        UUID geofenceId = UUID.fromString(created.getBody().get("geofenceId").asText());
        assertEquals("CIRCLE", created.getBody().get("shape").asText());
        assertEquals(0, created.getBody().get("armed").asLong());
        assertTrue(created.getBody().get("geometry").get("radiusM").asDouble() == 1000.0);

        ResponseEntity<JsonNode> duplicate = create(admin, "Kampala depot", circle(1000), false);
        assertEquals(HttpStatus.CONFLICT, duplicate.getStatusCode());
        assertEquals("GEOFENCE_NAME_TAKEN", duplicate.getBody().get("code").asText());

        Session operator = signInAsNewUser(admin, "OPERATOR");
        assertEquals(HttpStatus.CREATED,
                create(operator, "Entebbe road", circle(500), false).getStatusCode());

        Session analyst = signInAsNewUser(admin, "ANALYST");
        ResponseEntity<JsonNode> refused = create(analyst, "Analyst fence", circle(500), false);
        assertEquals(HttpStatus.FORBIDDEN, refused.getStatusCode());
        assertEquals("MANAGE_GEOFENCES", refused.getBody().get("permission").asText());
        assertEquals(1, auditCount(analyst, "OPS_FORBIDDEN", "DENIED"));
        assertTrue(jdbc.queryForObject("SELECT count(*) FROM geofence WHERE geofence_id = ?",
                Integer.class, geofenceId) == 1);

        ResponseEntity<JsonNode> tooFewPoints = create(admin, "Two points",
                Map.of("shape", "POLYGON", "points", List.of(
                        Map.of("latitude", 0.3, "longitude", 32.5),
                        Map.of("latitude", 0.4, "longitude", 32.6))), false);
        assertEquals(HttpStatus.BAD_REQUEST, tooFewPoints.getStatusCode());
        assertEquals("GEOFENCE_POLYGON_POINTS", tooFewPoints.getBody().get("code").asText());

        ResponseEntity<JsonNode> zeroRadius = create(admin, "Zero radius",
                Map.of("shape", "CIRCLE", "latitude", 0.3476, "longitude", 32.5825,
                        "radiusM", 0), false);
        assertEquals(HttpStatus.BAD_REQUEST, zeroRadius.getStatusCode());
        assertEquals("GEOFENCE_RADIUS_INVALID", zeroRadius.getBody().get("code").asText());

        ResponseEntity<JsonNode> noCsrf = rest.exchange("/v1/ops/geofences", HttpMethod.POST,
                new HttpEntity<>(createBody("No csrf", circle(1000), false),
                        sessionHeaders(admin, null)), JsonNode.class);
        assertEquals(HttpStatus.FORBIDDEN, noCsrf.getStatusCode());
        assertEquals("CSRF_REQUIRED", noCsrf.getBody().get("code").asText());

        Session viewer = signInAsNewUser(admin, "VIEWER");
        ResponseEntity<JsonNode> list = get(viewer, "/v1/ops/geofences");
        assertEquals(HttpStatus.OK, list.getStatusCode(), String.valueOf(list.getBody()));
        assertEquals(2, list.getBody().size());
    }

    @Test
    void armingNeedsConsentAndKeepsOnlyAMaskedLabel() {
        Session admin = signInAdmin();
        UUID geofenceId = UUID.fromString(create(admin, "Consent fence", circle(1000), false)
                .getBody().get("geofenceId").asText());

        int deniedBefore = auditCount(admin, "GEOFENCE_ARMED", "DENIED");
        ResponseEntity<JsonNode> unconsented = arm(admin, geofenceId, unconsentedClient);
        assertEquals(HttpStatus.FORBIDDEN, unconsented.getStatusCode());
        assertEquals("PRIVACY_NOT_SATISFIED", unconsented.getBody().get("code").asText());
        assertEquals(deniedBefore + 1, auditCount(admin, "GEOFENCE_ARMED", "DENIED"));

        int allowedBefore = auditCount(admin, "GEOFENCE_ARMED", "ALLOWED");
        ResponseEntity<JsonNode> armed = arm(admin, geofenceId, consentedClient);
        assertEquals(HttpStatus.CREATED, armed.getStatusCode(), String.valueOf(armed.getBody()));
        assertTrue(armed.getBody().get("created").asBoolean());
        JsonNode arm = armed.getBody().get("arm");
        assertNotNull(arm);
        assertFalse(arm.get("subjectLabel").asText().contains(MSISDN),
                "the arm must not carry the raw MSISDN");
        assertTrue(arm.get("subjectLabel").asText().startsWith("25677"));
        assertTrue(arm.get("subjectLabel").asText().contains("*"));
        assertEquals(64, arm.get("subjectRef").asText().length());
        assertTrue(arm.get("state") == null || arm.get("state").isNull(),
                "an arm has no state until a result is evaluated");
        assertEquals(allowedBefore + 1, auditCount(admin, "GEOFENCE_ARMED", "ALLOWED"));

        ResponseEntity<JsonNode> replay = arm(admin, geofenceId, consentedClient);
        assertEquals(HttpStatus.OK, replay.getStatusCode());
        assertFalse(replay.getBody().get("created").asBoolean());

        ResponseEntity<JsonNode> arms = get(admin, "/v1/ops/geofences/" + geofenceId + "/arms");
        assertEquals(1, arms.getBody().size());
        String subjectRef = arms.getBody().get(0).get("subjectRef").asText();

        ResponseEntity<JsonNode> disabled = patch(admin, geofenceId, Map.of("enabled", false));
        assertEquals(HttpStatus.OK, disabled.getStatusCode());
        ResponseEntity<JsonNode> refused = arm(admin, geofenceId, consentedClient);
        assertEquals(HttpStatus.CONFLICT, refused.getStatusCode());
        assertEquals("GEOFENCE_DISABLED", refused.getBody().get("code").asText());

        assertEquals(HttpStatus.NO_CONTENT, delete(admin,
                "/v1/ops/geofences/" + geofenceId + "/arms/" + subjectRef).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, delete(admin,
                "/v1/ops/geofences/" + geofenceId + "/arms/" + subjectRef).getStatusCode());
    }

    @Test
    void storedResultsProduceEnterThenLeaveThroughTheRealRequestPath() {
        Session admin = signInAdmin();
        UUID geofenceId = UUID.fromString(create(admin, "Depot circle", circle(1000), false)
                .getBody().get("geofenceId").asText());
        arm(admin, geofenceId, consentedClient);

        submitLocation(admin);
        Map<String, Object> armState = jdbc.queryForMap(
                "SELECT state FROM geofence_arm WHERE geofence_id = ?", geofenceId);
        assertEquals("INSIDE", armState.get("state"));

        StubLocator.latitude = OUTSIDE_LAT;
        submitLocation(admin);
        assertEquals("OUTSIDE", jdbc.queryForObject(
                "SELECT state FROM geofence_arm WHERE geofence_id = ?", String.class, geofenceId));

        ResponseEntity<JsonNode> events = get(admin, "/v1/ops/geofences/events?range=1h");
        assertEquals(HttpStatus.OK, events.getStatusCode(), String.valueOf(events.getBody()));
        assertEquals(2, events.getBody().get("total").asLong());
        assertEquals("LEAVE", events.getBody().get("items").get(0).get("eventType").asText());
        assertEquals("ENTER", events.getBody().get("items").get(1).get("eventType").asText());
        assertFalse(events.getBody().toString().contains(MSISDN));
        assertEquals(100, events.getBody().get("items").get(1).get("uncertaintyM").asInt());
        assertEquals("CELL_ID", events.getBody().get("items").get(1).get("method").asText());

        assertEquals(1, get(admin, "/v1/ops/geofences/events?range=1h&type=ENTER")
                .getBody().get("total").asLong());
        ResponseEntity<JsonNode> badType = get(admin, "/v1/ops/geofences/events?range=1h&type=JUMP");
        assertEquals(HttpStatus.BAD_REQUEST, badType.getStatusCode());
        assertEquals("GEOFENCE_EVENT_NOT_SUPPORTED", badType.getBody().get("code").asText());

        JsonNode summary = get(admin, "/v1/ops/geofences/summary?range=1h").getBody();
        assertEquals(1, summary.get("counts").get("armed").asLong());
        assertEquals(0, summary.get("counts").get("inside").asLong());
        assertEquals(1, summary.get("counts").get("entering").asLong());
        assertEquals(1, summary.get("counts").get("leaving").asLong());
        assertTrue(summary.get("reporting").get("areaEventsFromStoredResults").get("available")
                .asBoolean());
        assertFalse(summary.get("reporting").get("networkAreaEvents").get("available").asBoolean());
        assertFalse(summary.get("reporting").get("periodicLocation").get("available").asBoolean());

        ResponseEntity<JsonNode> noSession = rest.getForEntity("/v1/ops/geofences", JsonNode.class);
        assertEquals(HttpStatus.UNAUTHORIZED, noSession.getStatusCode());
    }

    @Test
    void insideReportsFollowTheFlagAndDisabledFencesStopEvaluating() {
        Session admin = signInAdmin();
        UUID reporting = UUID.fromString(create(admin, "Reporting fence", circle(1000), true)
                .getBody().get("geofenceId").asText());
        UUID quiet = UUID.fromString(create(admin, "Quiet fence", circle(1000), false)
                .getBody().get("geofenceId").asText());

        arm(admin, reporting, consentedClient);
        arm(admin, quiet, consentedClient);

        submitLocation(admin);
        submitLocation(admin);

        assertEquals(1, eventCount(reporting, "ENTER"));
        assertEquals(1, eventCount(reporting, "INSIDE"));
        assertEquals(1, eventCount(quiet, "ENTER"));
        assertEquals(0, eventCount(quiet, "INSIDE"));

        patch(admin, quiet, Map.of("enabled", false));
        submitLocation(admin);
        assertEquals(1, eventCount(quiet, "ENTER"), "a disabled fence is not evaluated");
        assertEquals(1, eventCount(reporting, "ENTER"));

        JsonNode inside = get(admin, "/v1/ops/geofences/arms?state=INSIDE").getBody();
        assertEquals(1, inside.size());
        assertEquals(reporting.toString(), inside.get(0).get("geofenceId").asText());

        ResponseEntity<JsonNode> badState =
                get(admin, "/v1/ops/geofences/arms?state=SIDEWAYS");
        assertEquals(HttpStatus.BAD_REQUEST, badState.getStatusCode());
        assertEquals("GEOFENCE_STATE_NOT_SUPPORTED", badState.getBody().get("code").asText());
    }

    // ---------------------------------------------------------------- helpers

    private UUID seedClient(String name, boolean enabled) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO api_client (client_id, tenant_id, name, key_hash, purposes,"
                        + " max_accuracy_m, enabled) VALUES (?, 1, ?, ?, ARRAY[?], 300, ?)",
                id, name, hex64(id), PURPOSE, enabled);
        return id;
    }

    private Map<String, Object> circle(double radiusM) {
        Map<String, Object> geometry = new LinkedHashMap<>();
        geometry.put("shape", "CIRCLE");
        geometry.put("latitude", INSIDE_LAT);
        geometry.put("longitude", INSIDE_LON);
        geometry.put("radiusM", radiusM);
        return geometry;
    }

    private Map<String, Object> createBody(String name, Map<String, Object> geometry,
                                           boolean reportInside) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("description", "Test fence");
        body.put("group", "Kampala");
        body.put("reportInside", reportInside);
        body.put("geometry", geometry);
        return body;
    }

    private ResponseEntity<JsonNode> create(Session session, String name,
                                            Map<String, Object> geometry, boolean reportInside) {
        return rest.exchange("/v1/ops/geofences", HttpMethod.POST,
                new HttpEntity<>(createBody(name, geometry, reportInside),
                        sessionHeaders(session, session.csrf())), JsonNode.class);
    }

    private ResponseEntity<JsonNode> patch(Session session, UUID geofenceId,
                                           Map<String, Object> body) {
        return rest.exchange("/v1/ops/geofences/" + geofenceId, HttpMethod.PATCH,
                new HttpEntity<>(body, sessionHeaders(session, session.csrf())), JsonNode.class);
    }

    private ResponseEntity<JsonNode> arm(Session session, UUID geofenceId, UUID clientId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("clientId", clientId.toString());
        body.put("msisdn", MSISDN);
        body.put("purpose", PURPOSE);
        return rest.exchange("/v1/ops/geofences/" + geofenceId + "/arm", HttpMethod.POST,
                new HttpEntity<>(body, sessionHeaders(session, session.csrf())), JsonNode.class);
    }

    private void submitLocation(Session session) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("clientId", consentedClient.toString());
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
                        + " WHERE geofence_id = ? AND event_type = ?",
                Integer.class, geofenceId, type);
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

    private ResponseEntity<JsonNode> delete(Session session, String path) {
        return rest.exchange(path, HttpMethod.DELETE,
                new HttpEntity<>(sessionHeaders(session, session.csrf())), JsonNode.class);
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

    private record Session(String token, String csrf, JsonNode body) {
    }
}
