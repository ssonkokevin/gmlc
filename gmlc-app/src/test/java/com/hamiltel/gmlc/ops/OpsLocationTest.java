package com.hamiltel.gmlc.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.hamiltel.gmlc.client.ApiClient;
import com.hamiltel.gmlc.consent.ConsentGrantRequest;
import com.hamiltel.gmlc.consent.ConsentService;
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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A console location request must be the same governed request an API client
 * would get: consent and purpose decide, the state is the stored state, the
 * person is audited separately from the client, and a role without LOCATE or
 * a request without the CSRF token is refused.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "gmlc.ops.cookie-secure=false",
        "gmlc.ops.argon2-memory-kib=4096",
        "gmlc.ops.argon2-iterations=1",
        "gmlc.ops.environment=LAB",
        "gmlc.ops.bootstrap-username=root",
        "gmlc.ops.bootstrap-password=" + OpsLocationTest.ADMIN_PASSWORD,
        "gmlc.ops.bootstrap-totp-secret=" + OpsLocationTest.ADMIN_TOTP,
        "gmlc.ops.alerts.enabled=false"
})
class OpsLocationTest {

    static final String ADMIN_PASSWORD = "bootstrap-password-123";
    static final String ADMIN_TOTP = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP";

    private static final String MSISDN = "256772000111";
    private static final String PURPOSE = "COMMERCIAL_LBS";

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ConsentService consents;

    private UUID consentedClient;
    private UUID unconsentedClient;
    private UUID disabledClient;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        EmbeddedPostgresSupport.register(registry);
    }

    @BeforeEach
    void seed() {
        rest.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String consentedName = "Consented LBS " + suffix;
        consentedClient = seedClient(consentedName, true);
        unconsentedClient = seedClient("No consent " + suffix, true);
        disabledClient = seedClient("Disabled " + suffix, false);

        ApiClient client = new ApiClient(consentedClient, 1, consentedName, Set.of(PURPOSE),
                300, 5, 1000, List.of());
        consents.grant(client, new ConsentGrantRequest(MSISDN, null, PURPOSE, "PORTAL",
                Instant.now().plus(30, ChronoUnit.DAYS), null));
    }

    @Test
    void theClientPickerNeedsLocateAndLeavesDisabledClientsOut() {
        Session admin = signInAdmin();
        ResponseEntity<JsonNode> list = get(admin, "/v1/ops/clients");
        assertEquals(HttpStatus.OK, list.getStatusCode(), String.valueOf(list.getBody()));

        String names = list.getBody().toString();
        assertTrue(names.contains("Consented LBS"));
        assertFalse(names.contains("Disabled"));
        assertFalse(names.contains("key_hash"));

        Session analyst = signInAsNewUser(admin, "ANALYST");
        ResponseEntity<JsonNode> refused = get(analyst, "/v1/ops/clients");
        assertEquals(HttpStatus.FORBIDDEN, refused.getStatusCode());
        assertEquals("PERMISSION_DENIED", refused.getBody().get("code").asText());
    }

    @Test
    void submissionRequiresCsrfAndTheLocatePermission() {
        Session admin = signInAdmin();
        Map<String, Object> body = submission(consentedClient, MSISDN);

        ResponseEntity<JsonNode> noCsrf = rest.exchange("/v1/ops/locations", HttpMethod.POST,
                new HttpEntity<>(body, sessionHeaders(admin, null)), JsonNode.class);
        assertEquals(HttpStatus.FORBIDDEN, noCsrf.getStatusCode());
        assertEquals("CSRF_REQUIRED", noCsrf.getBody().get("code").asText());

        Session analyst = signInAsNewUser(admin, "ANALYST");
        ResponseEntity<JsonNode> noPermission = rest.exchange("/v1/ops/locations", HttpMethod.POST,
                new HttpEntity<>(body, sessionHeaders(analyst, analyst.csrf())), JsonNode.class);
        assertEquals(HttpStatus.FORBIDDEN, noPermission.getStatusCode());
        assertEquals("PERMISSION_DENIED", noPermission.getBody().get("code").asText());
    }

    @Test
    void anUnknownOrDisabledClientIsNotGuessable() {
        Session admin = signInAdmin();

        ResponseEntity<JsonNode> unknown = post(admin, submission(UUID.randomUUID(), MSISDN));
        assertEquals(HttpStatus.NOT_FOUND, unknown.getStatusCode());

        ResponseEntity<JsonNode> disabled = post(admin, submission(disabledClient, MSISDN));
        assertEquals(HttpStatus.NOT_FOUND, disabled.getStatusCode());
    }

    @Test
    void withoutConsentTheRequestIsRefusedAndThePersonIsAudited() {
        Session admin = signInAdmin();

        ResponseEntity<JsonNode> refused = post(admin, submission(unconsentedClient, MSISDN));
        assertEquals(HttpStatus.FORBIDDEN, refused.getStatusCode());
        assertEquals("PRIVACY_NOT_SATISFIED", refused.getBody().get("code").asText());

        Integer denied = jdbc.queryForObject("SELECT count(*) FROM audit_event"
                        + " WHERE action = 'OPS_LOCATION_SUBMITTED' AND outcome = 'DENIED'"
                        + " AND actor_user_id = ?::uuid",
                Integer.class, admin.body().get("userId").asText());
        assertEquals(1, denied);
    }

    @Test
    void aPermittedSubmissionUsesTheClientServiceAndShowsItsTimeline() {
        Session admin = signInAdmin();
        Map<String, Object> body = submission(consentedClient, MSISDN);
        body.put("idempotencyKey", "console-" + UUID.randomUUID());

        ResponseEntity<JsonNode> accepted = post(admin, body);
        assertTrue(accepted.getStatusCode() == HttpStatus.ACCEPTED
                || accepted.getStatusCode() == HttpStatus.OK, String.valueOf(accepted.getBody()));
        UUID requestId = UUID.fromString(accepted.getBody().get("requestId").asText());
        assertEquals(PURPOSE, accepted.getBody().get("purpose").asText());

        Map<String, Object> stored = jdbc.queryForMap("SELECT client_id, purpose, state,"
                + " subscriber_msisdn FROM location_request WHERE request_id = ?", requestId);
        assertEquals(consentedClient, stored.get("client_id"));
        assertEquals(PURPOSE, stored.get("purpose"));
        assertNotNull(stored.get("state"));

        Integer allowed = jdbc.queryForObject("SELECT count(*) FROM audit_event"
                        + " WHERE action = 'OPS_LOCATION_SUBMITTED' AND outcome = 'ALLOWED'"
                        + " AND actor_user_id = ?::uuid AND detail->>'requestId' = ?",
                Integer.class, admin.body().get("userId").asText(), requestId.toString());
        assertEquals(1, allowed);

        ResponseEntity<JsonNode> detail = get(admin, "/v1/ops/requests/" + requestId);
        assertEquals(HttpStatus.OK, detail.getStatusCode(), String.valueOf(detail.getBody()));
        assertTrue(detail.getBody().get("identifiers").get("masked").asBoolean());
        assertFalse(detail.getBody().get("identifiers").get("msisdn").asText().contains(MSISDN));
        JsonNode timeline = detail.getBody().get("timeline");
        assertTrue(timeline.isArray());
        assertTrue(timeline.size() > 0, "the stored timeline should name the transitions");
        assertTrue(timeline.toString().contains("REQUEST_ACCEPTED"));

        ResponseEntity<JsonNode> replay = post(admin, body);
        assertEquals(requestId, UUID.fromString(replay.getBody().get("requestId").asText()));
        assertTrue(replay.getBody().get("replayed").asBoolean());
        Integer rows = jdbc.queryForObject("SELECT count(*) FROM location_request"
                        + " WHERE client_id = ? AND idempotency_key = ?",
                Integer.class, consentedClient, body.get("idempotencyKey"));
        assertEquals(1, rows);
    }

    @Test
    void anAccuracyBetterThanTheClientBuysIsRefused() {
        Session admin = signInAdmin();
        Map<String, Object> body = submission(consentedClient, MSISDN);
        body.put("accuracyMeters", 100);

        ResponseEntity<JsonNode> refused = post(admin, body);
        assertEquals(HttpStatus.BAD_REQUEST, refused.getStatusCode());
        assertEquals("ACCURACY_NOT_ALLOWED", refused.getBody().get("code").asText());
    }

    // ---------------------------------------------------------------- helpers

    private UUID seedClient(String name, boolean enabled) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO api_client (client_id, tenant_id, name, key_hash, purposes,"
                        + " max_accuracy_m, enabled) VALUES (?, 1, ?, ?, ARRAY[?], 300, ?)",
                id, name, hex64(id), PURPOSE, enabled);
        return id;
    }

    private Map<String, Object> submission(UUID clientId, String msisdn) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("clientId", clientId.toString());
        body.put("msisdn", msisdn);
        body.put("purpose", PURPOSE);
        body.put("accuracyMeters", 300);
        return body;
    }

    private ResponseEntity<JsonNode> post(Session session, Map<String, Object> body) {
        return rest.exchange("/v1/ops/locations", HttpMethod.POST,
                new HttpEntity<>(body, sessionHeaders(session, session.csrf())), JsonNode.class);
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

    private record Session(String token, String csrf, JsonNode body) {
    }
}
