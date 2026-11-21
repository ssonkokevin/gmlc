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
 * A tracked subject is a roster row, not a subscription: every trail point is
 * a result the governed request path already stored. The locator is stubbed so
 * the test can move the subject and read the transition the real path wrote.
 *
 * <p>Adding needs consent; reads of a fix need the permissions that govern
 * location data; the client-facing last-known is scoped to the client that
 * added the subject; and no response carries the identifier itself.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "gmlc.ops.cookie-secure=false",
        "gmlc.ops.argon2-memory-kib=4096",
        "gmlc.ops.argon2-iterations=1",
        "gmlc.ops.environment=LAB",
        "gmlc.ops.bootstrap-username=root",
        "gmlc.ops.bootstrap-password=" + OpsTrackingTest.ADMIN_PASSWORD,
        "gmlc.ops.bootstrap-totp-secret=" + OpsTrackingTest.ADMIN_TOTP,
        "gmlc.ops.alerts.enabled=false",
        "gmlc.webhooks.enabled=false",
        "gmlc.webhooks.initial-delay-seconds=3600"
})
@Import(OpsTrackingTest.StubLocator.class)
class OpsTrackingTest {

    static final String ADMIN_PASSWORD = "bootstrap-password-123";
    static final String ADMIN_TOTP = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP";

    private static final String MSISDN = "256772000444";
    private static final String PURPOSE = "FLEET_MANAGEMENT";
    private static final double FIRST_LAT = 0.3476;
    private static final double FIRST_LON = 32.5825;
    private static final double SECOND_LAT = 0.3676;

    @TestConfiguration
    static class StubLocator {

        static volatile double latitude = FIRST_LAT;
        static volatile double longitude = FIRST_LON;

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

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        EmbeddedPostgresSupport.register(registry);
    }

    @BeforeEach
    void reset() {
        rest.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
        StubLocator.latitude = FIRST_LAT;
        StubLocator.longitude = FIRST_LON;
        jdbc.update("DELETE FROM tracked_subject");
        jdbc.update("DELETE FROM cdr");
        jdbc.update("DELETE FROM location_result");
        jdbc.update("DELETE FROM location_request");
    }

    @Test
    void addingASubjectNeedsConsentAndNeverReturnsTheIdentifier() {
        Session admin = signInAdmin();
        Client owner = registerClient("fleet-owner", PURPOSE);
        Client other = registerClient("other-owner", PURPOSE);
        grantConsent(owner, MSISDN, PURPOSE);

        ResponseEntity<JsonNode> added = add(admin, owner.id(), MSISDN, null, PURPOSE);
        assertEquals(HttpStatus.CREATED, added.getStatusCode(), String.valueOf(added.getBody()));
        JsonNode subject = added.getBody();
        UUID trackedId = UUID.fromString(subject.get("trackedId").asText());
        assertEquals(owner.id().toString(), subject.get("clientId").asText());
        assertEquals(PURPOSE, subject.get("purpose").asText());
        assertTrue(subject.get("enabled").asBoolean());
        assertEquals(0, subject.get("fixes").asLong());
        assertTrue(subject.get("lastFix") == null || subject.get("lastFix").isNull(),
                "no fix has been stored yet");
        assertFalse(subject.toString().contains(MSISDN), "the roster view must not carry the MSISDN");
        assertTrue(subject.get("subjectLabel").asText().startsWith("25677"));
        assertTrue(subject.get("subjectLabel").asText().contains("*"));

        ResponseEntity<JsonNode> duplicate = add(admin, owner.id(), MSISDN, null, PURPOSE);
        assertEquals(HttpStatus.CONFLICT, duplicate.getStatusCode());
        assertEquals("SUBJECT_ALREADY_TRACKED", duplicate.getBody().get("code").asText());

        int deniedBefore = auditCount(admin, "TRACKING_SUBJECT_ADDED", "DENIED");
        ResponseEntity<JsonNode> unconsented = add(admin, other.id(), MSISDN, null, PURPOSE);
        assertEquals(HttpStatus.FORBIDDEN, unconsented.getStatusCode());
        assertEquals("PRIVACY_NOT_SATISFIED", unconsented.getBody().get("code").asText());
        assertEquals(deniedBefore + 1, auditCount(admin, "TRACKING_SUBJECT_ADDED", "DENIED"));

        Client fleetOnly = registerClient("fleet-only", PURPOSE);
        ResponseEntity<JsonNode> wrongPurpose = add(admin, fleetOnly.id(), MSISDN, null, "SAFETY");
        assertEquals(HttpStatus.BAD_REQUEST, wrongPurpose.getStatusCode());
        assertEquals("PURPOSE_NOT_ALLOWED", wrongPurpose.getBody().get("code").asText());

        Client both = registerClient("both-labels", PURPOSE);
        ResponseEntity<JsonNode> ambiguous =
                add(admin, both.id(), MSISDN, "256770000000001", PURPOSE);
        assertEquals(HttpStatus.BAD_REQUEST, ambiguous.getStatusCode());
        assertEquals("SUBJECT_IDENTIFIER_AMBIGUOUS", ambiguous.getBody().get("code").asText());

        ResponseEntity<JsonNode> noCsrf = rest.exchange("/v1/ops/tracking", HttpMethod.POST,
                new HttpEntity<>(addBody(owner.id(), MSISDN, null, PURPOSE),
                        sessionHeaders(admin, null)), JsonNode.class);
        assertEquals(HttpStatus.FORBIDDEN, noCsrf.getStatusCode());
        assertEquals("CSRF_REQUIRED", noCsrf.getBody().get("code").asText());

        Session analyst = signInAsNewUser(admin, "ANALYST");
        ResponseEntity<JsonNode> refused = add(analyst, owner.id(), MSISDN, null, PURPOSE);
        assertEquals(HttpStatus.FORBIDDEN, refused.getStatusCode());
        assertEquals("LOCATE", refused.getBody().get("permission").asText());

        ResponseEntity<JsonNode> list = get(admin, "/v1/ops/tracking");
        assertEquals(HttpStatus.OK, list.getStatusCode(), String.valueOf(list.getBody()));
        assertEquals(1, list.getBody().size());
        assertFalse(list.getBody().toString().contains(MSISDN));

        Session viewer = signInAsNewUser(admin, "VIEWER");
        assertEquals(HttpStatus.OK, get(viewer, "/v1/ops/tracking").getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, add(viewer, owner.id(), MSISDN, null, PURPOSE)
                .getStatusCode());

        assertEquals(HttpStatus.NO_CONTENT, delete(admin, "/v1/ops/tracking/" + trackedId)
                .getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, get(admin, "/v1/ops/tracking/" + trackedId)
                .getStatusCode());
    }

    @Test
    void trailAndLastKnownComeFromStoredResultsOnly() {
        Session admin = signInAdmin();
        Client owner = registerClient("trail-owner", PURPOSE);
        Client stranger = registerClient("trail-stranger", PURPOSE);
        grantConsent(owner, MSISDN, PURPOSE);

        UUID trackedId = UUID.fromString(add(admin, owner.id(), MSISDN, null, PURPOSE)
                .getBody().get("trackedId").asText());

        ResponseEntity<JsonNode> empty = lastKnown(owner, trackedId);
        assertEquals(HttpStatus.OK, empty.getStatusCode(), String.valueOf(empty.getBody()));
        assertFalse(empty.getBody().get("found").asBoolean());
        assertEquals("NO_LOCATION_YET", empty.getBody().get("reason").asText());
        assertEquals("LAB_SIM", empty.getBody().get("dataSource").asText());

        UUID firstRequest = submitLocation(admin, owner, FIRST_LAT);
        StubLocator.latitude = SECOND_LAT;
        UUID secondRequest = submitLocation(admin, owner, SECOND_LAT);
        jdbc.update("UPDATE location_result SET produced_at = ? WHERE request_id = ?",
                java.sql.Timestamp.from(Instant.now().minus(120, ChronoUnit.SECONDS)), firstRequest);
        jdbc.update("UPDATE location_result SET produced_at = ? WHERE request_id = ?",
                java.sql.Timestamp.from(Instant.now().minus(60, ChronoUnit.SECONDS)), secondRequest);

        ResponseEntity<JsonNode> trail = get(admin, "/v1/ops/tracking/" + trackedId + "/trail");
        assertEquals(HttpStatus.OK, trail.getStatusCode(), String.valueOf(trail.getBody()));
        assertEquals(2, trail.getBody().size());
        JsonNode latest = trail.getBody().get(0);
        assertEquals(SECOND_LAT, latest.get("latitude").asDouble(), 0.000001);
        assertEquals("CELL_ID", latest.get("method").asText());
        assertEquals("CIRCLE", latest.get("shape").asText());
        assertEquals(100, latest.get("uncertaintyM").asInt());
        assertEquals(90, latest.get("confidence").asInt());
        assertEquals(5, latest.get("ageSeconds").asInt());
        assertFalse(trail.getBody().toString().contains(MSISDN));

        JsonNode detail = get(admin, "/v1/ops/tracking/" + trackedId).getBody();
        assertEquals(2, detail.get("fixes").asLong());
        assertEquals(SECOND_LAT, detail.get("lastFix").get("latitude").asDouble(), 0.000001);

        JsonNode summary = get(admin, "/v1/ops/tracking/summary").getBody();
        assertEquals(1, summary.get("total").asLong());
        assertEquals(1, summary.get("enabled").asLong());
        assertEquals(1, summary.get("withFix").asLong());
        assertEquals(2, summary.get("fixes").asLong());
        assertEquals("LAB_SIM", summary.get("dataSource").asText());
        assertTrue(summary.get("reporting").get("trailsFromStoredResults").get("available")
                .asBoolean());
        assertFalse(summary.get("reporting").get("liveNetworkTracking").get("available")
                .asBoolean());
        assertFalse(summary.get("reporting").get("scheduledTracking").get("available")
                .asBoolean());

        ResponseEntity<JsonNode> known = lastKnown(owner, trackedId);
        assertEquals(HttpStatus.OK, known.getStatusCode(), String.valueOf(known.getBody()));
        assertTrue(known.getBody().get("found").asBoolean());
        JsonNode fix = known.getBody().get("fix");
        assertEquals(SECOND_LAT, fix.get("latitude").asDouble(), 0.000001);
        assertEquals(100, fix.get("uncertaintyM").asInt());
        assertEquals(90, fix.get("confidence").asInt());
        assertEquals(5, fix.get("ageSeconds").asInt());
        assertEquals("LAB_SIM", known.getBody().get("dataSource").asText());
        assertFalse(known.getBody().toString().contains(MSISDN));

        ResponseEntity<JsonNode> notYours = lastKnown(stranger, trackedId);
        assertEquals(HttpStatus.NOT_FOUND, notYours.getStatusCode());
        assertEquals("TRACKED_SUBJECT_NOT_FOUND", notYours.getBody().get("code").asText());

        ResponseEntity<JsonNode> noKey = rest.getForEntity(
                "/v1/subscribers/" + trackedId + "/last-known", JsonNode.class);
        assertEquals(HttpStatus.UNAUTHORIZED, noKey.getStatusCode());

        assertEquals(HttpStatus.OK, patch(admin, trackedId, Map.of("enabled", false))
                .getStatusCode());
        ResponseEntity<JsonNode> disabled = lastKnown(owner, trackedId);
        assertFalse(disabled.getBody().get("found").asBoolean());
        assertEquals("TRACKING_DISABLED", disabled.getBody().get("reason").asText());

        patch(admin, trackedId, Map.of("enabled", true));
        revokeConsent(owner, MSISDN, PURPOSE);
        ResponseEntity<JsonNode> revoked = lastKnown(owner, trackedId);
        assertEquals(HttpStatus.OK, revoked.getStatusCode());
        assertFalse(revoked.getBody().get("found").asBoolean());
        assertEquals("NO_CONSENT", revoked.getBody().get("reason").asText());
        assertTrue(revoked.getBody().get("fix") == null || revoked.getBody().get("fix").isNull());

        assertEquals(HttpStatus.BAD_REQUEST, get(admin,
                "/v1/ops/tracking/" + trackedId + "/trail?limit=0").getStatusCode());
    }

    // ---------------------------------------------------------------- helpers

    private Client registerClient(String prefix, String... purposes) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", prefix + "-" + UUID.randomUUID().toString().substring(0, 8));
        body.put("purposes", List.of(purposes));
        body.put("maxAccuracyM", 300);
        ResponseEntity<JsonNode> response = rest.exchange("/v1/admin/clients", HttpMethod.POST,
                new HttpEntity<>(body, EmbeddedPostgresSupport.adminHeaders()), JsonNode.class);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(), String.valueOf(response.getBody()));
        return new Client(UUID.fromString(response.getBody().get("clientId").asText()),
                response.getBody().get("apiKey").asText());
    }

    private void grantConsent(Client client, String msisdn, String purpose) {
        ApiClient apiClient = new ApiClient(client.id(), 1, "test", Set.of(purpose),
                300, 5, 1000, List.of());
        consents.grant(apiClient, new ConsentGrantRequest(msisdn, null, purpose, "PORTAL",
                Instant.now().plus(30, ChronoUnit.DAYS), null));
    }

    private void revokeConsent(Client client, String msisdn, String purpose) {
        UUID consentId = jdbc.queryForObject("SELECT consent_id FROM consent"
                        + " WHERE client_id = ? AND purpose = ? AND status = 'GRANTED'"
                        + " ORDER BY granted_at DESC LIMIT 1",
                UUID.class, client.id(), purpose);
        assertNotNull(consentId);
        jdbc.update("UPDATE consent SET status = 'REVOKED', revoked_at = now()"
                + " WHERE consent_id = ?", consentId);
    }

    private Map<String, Object> addBody(UUID clientId, String msisdn, String imsi,
                                        String purpose) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("clientId", clientId.toString());
        if (msisdn != null) {
            body.put("msisdn", msisdn);
        }
        if (imsi != null) {
            body.put("imsi", imsi);
        }
        body.put("purpose", purpose);
        return body;
    }

    private ResponseEntity<JsonNode> add(Session session, UUID clientId, String msisdn,
                                         String imsi, String purpose) {
        return rest.exchange("/v1/ops/tracking", HttpMethod.POST,
                new HttpEntity<>(addBody(clientId, msisdn, imsi, purpose),
                        sessionHeaders(session, session.csrf())), JsonNode.class);
    }

    private ResponseEntity<JsonNode> patch(Session session, UUID trackedId, Map<String, Object> body) {
        return rest.exchange("/v1/ops/tracking/" + trackedId, HttpMethod.PATCH,
                new HttpEntity<>(body, sessionHeaders(session, session.csrf())), JsonNode.class);
    }

    private UUID submitLocation(Session session, Client client, double latitude) {
        StubLocator.latitude = latitude;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("clientId", client.id().toString());
        body.put("msisdn", MSISDN);
        body.put("purpose", PURPOSE);
        body.put("accuracyMeters", 300);
        ResponseEntity<JsonNode> response = rest.exchange("/v1/ops/locations", HttpMethod.POST,
                new HttpEntity<>(body, sessionHeaders(session, session.csrf())), JsonNode.class);
        assertTrue(response.getStatusCode() == HttpStatus.OK
                        || response.getStatusCode() == HttpStatus.ACCEPTED,
                String.valueOf(response.getBody()));
        return UUID.fromString(response.getBody().get("requestId").asText());
    }

    private ResponseEntity<JsonNode> lastKnown(Client client, UUID trackedId) {
        return rest.exchange("/v1/subscribers/" + trackedId + "/last-known", HttpMethod.GET,
                new HttpEntity<>(EmbeddedPostgresSupport.bearer(client.apiKey())), JsonNode.class);
    }

    private ResponseEntity<JsonNode> get(Session session, String path) {
        return rest.exchange(path, HttpMethod.GET,
                new HttpEntity<>(sessionHeaders(session, null)), JsonNode.class);
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

    private record Session(String token, String csrf, JsonNode body) {
    }

    private record Client(UUID id, String apiKey) {
    }
}
