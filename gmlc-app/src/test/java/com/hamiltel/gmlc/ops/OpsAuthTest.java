package com.hamiltel.gmlc.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.hamiltel.gmlc.support.EmbeddedPostgresSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The console's authentication contract: password plus TOTP, a hardened
 * cookie, CSRF on every write, a role check on every administered route, an
 * idle clock, a lockout and an audit trail. The session endpoints are the only
 * ones reachable without a session, and no API key opens the surface.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "gmlc.ops.cookie-secure=false",
        "gmlc.ops.argon2-memory-kib=4096",
        "gmlc.ops.argon2-iterations=1",
        "gmlc.ops.lockout-threshold=3",
        "gmlc.ops.lockout-minutes=15",
        "gmlc.ops.bootstrap-username=root",
        "gmlc.ops.bootstrap-password=" + OpsAuthTest.ADMIN_PASSWORD,
        "gmlc.ops.bootstrap-totp-secret=" + OpsAuthTest.ADMIN_TOTP
})
class OpsAuthTest {

    static final String ADMIN_PASSWORD = "bootstrap-password-123";
    static final String ADMIN_TOTP = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP";

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcTemplate jdbc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        EmbeddedPostgresSupport.register(registry);
    }

    /**
     * The default HttpURLConnection client cannot send PATCH, and it retries a
     * 401 in streaming mode, which breaks every deliberate bad-credential
     * test. The JDK HTTP client handles both.
     */
    @BeforeEach
    void useJdkHttpClient() {
        rest.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
    }

    @Test
    void signingInNeedsBothFactors() {
        assertEquals(HttpStatus.UNAUTHORIZED,
                login("root", ADMIN_PASSWORD, "000000").getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,
                login("root", ADMIN_PASSWORD + "-wrong", totp(ADMIN_TOTP)).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,
                login("nobody-here", ADMIN_PASSWORD, totp(ADMIN_TOTP)).getStatusCode());
    }

    @Test
    void aSuccessfulSignInSetsAHardenedCookieAndReturnsThePermissionSet() {
        ResponseEntity<JsonNode> response = login("root", ADMIN_PASSWORD, totp(ADMIN_TOTP));
        assertEquals(HttpStatus.OK, response.getStatusCode());

        String setCookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertNotNull(setCookie);
        assertTrue(setCookie.contains("HttpOnly"));
        assertTrue(setCookie.contains("SameSite=Strict"));
        assertTrue(setCookie.contains("Path=/v1/ops"));
        assertTrue(setCookie.contains("Max-Age="));
        assertTrue(!setCookie.contains("Secure"), "the test profile runs plain HTTP");

        JsonNode body = response.getBody();
        assertEquals("ADMIN", body.get("role").asText());
        assertEquals("root", body.get("username").asText());
        assertFalse(body.get("csrfToken").asText().isBlank());
        assertTrue(body.get("permissions").toString().contains("MANAGE_USERS"));
        assertTrue(body.get("permissions").toString().contains("LOCATE"));
    }

    @Test
    void theSessionCookieIsTheOnlyWayIn() {
        assertEquals(HttpStatus.UNAUTHORIZED,
                rest.getForEntity("/v1/ops/me", JsonNode.class).getStatusCode());

        JsonNode client = EmbeddedPostgresSupport.registerClient(rest, "ops-key-test");
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(client.get("apiKey").asText());
        ResponseEntity<JsonNode> withKey = rest.exchange("/v1/ops/me", HttpMethod.GET,
                new HttpEntity<>(headers), JsonNode.class);
        assertEquals(HttpStatus.UNAUTHORIZED, withKey.getStatusCode());

        Session session = signInAdmin();
        assertEquals(HttpStatus.OK, me(session).getStatusCode());
    }

    @Test
    void stateChangingCallsNeedTheCsrfToken() {
        Session admin = signInAdmin();
        Map<String, Object> body = userBody(unique("csrf"), "OPERATOR");

        ResponseEntity<JsonNode> missing = rest.exchange("/v1/ops/users", HttpMethod.POST,
                new HttpEntity<>(body, sessionHeaders(admin, null)), JsonNode.class);
        assertEquals(HttpStatus.FORBIDDEN, missing.getStatusCode());
        assertEquals("CSRF_REQUIRED", missing.getBody().get("code").asText());

        ResponseEntity<JsonNode> wrong = rest.exchange("/v1/ops/users", HttpMethod.POST,
                new HttpEntity<>(body, sessionHeaders(admin, "not-the-token")), JsonNode.class);
        assertEquals(HttpStatus.FORBIDDEN, wrong.getStatusCode());

        ResponseEntity<JsonNode> correct = rest.exchange("/v1/ops/users", HttpMethod.POST,
                new HttpEntity<>(body, sessionHeaders(admin, admin.csrf())), JsonNode.class);
        assertEquals(HttpStatus.CREATED, correct.getStatusCode());
    }

    @Test
    void aViewerCanReadButCannotAdminister() {
        Session admin = signInAdmin();
        JsonNode created = createUser(admin, unique("viewer"), "VIEWER");
        Session viewer = signIn(created.get("user").get("username").asText(),
                created.get("oneTimePassword").asText(), created.get("totpSecret").asText());

        assertEquals(HttpStatus.OK, me(viewer).getStatusCode());

        ResponseEntity<JsonNode> list = rest.exchange("/v1/ops/users", HttpMethod.GET,
                new HttpEntity<>(sessionHeaders(viewer, null)), JsonNode.class);
        assertEquals(HttpStatus.FORBIDDEN, list.getStatusCode());
        assertEquals("PERMISSION_DENIED", list.getBody().get("code").asText());

        Long denied = jdbc.queryForObject("SELECT count(*) FROM audit_event"
                + " WHERE action = 'OPS_FORBIDDEN' AND actor_user_id = ?", Long.class,
                UUID.fromString(viewer.body().get("userId").asText()));
        assertNotNull(denied);
        assertTrue(denied >= 1);
    }

    @Test
    void aDisabledAccountLosesItsSessionAndItsLogin() {
        Session admin = signInAdmin();
        JsonNode created = createUser(admin, unique("disabled"), "OPERATOR");
        String username = created.get("user").get("username").asText();
        Session operator = signIn(username, created.get("oneTimePassword").asText(),
                created.get("totpSecret").asText());
        assertEquals(HttpStatus.OK, me(operator).getStatusCode());

        UUID userId = UUID.fromString(created.get("user").get("userId").asText());
        ResponseEntity<JsonNode> patch = rest.exchange("/v1/ops/users/" + userId, HttpMethod.PATCH,
                new HttpEntity<>(Map.of("enabled", false), sessionHeaders(admin, admin.csrf())),
                JsonNode.class);
        assertEquals(HttpStatus.OK, patch.getStatusCode());

        assertEquals(HttpStatus.UNAUTHORIZED, me(operator).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, login(username, created.get("oneTimePassword").asText(),
                totp(created.get("totpSecret").asText())).getStatusCode());
    }

    @Test
    void anIdleSessionExpires() {
        Session session = signInAdmin();
        jdbc.update("UPDATE ops_session SET idle_expires_at = now() - interval '1 second'"
                + " WHERE token_hash = ?", OpsSessionService.sha256(session.token()));

        assertEquals(HttpStatus.UNAUTHORIZED, me(session).getStatusCode());
        String revoked = jdbc.queryForObject("SELECT revoked_at::text FROM ops_session"
                + " WHERE token_hash = ?", String.class, OpsSessionService.sha256(session.token()));
        assertNotNull(revoked);
    }

    @Test
    void signingOutEndsTheSession() {
        Session session = signInAdmin();
        ResponseEntity<JsonNode> out = rest.exchange("/v1/ops/session", HttpMethod.DELETE,
                new HttpEntity<>(sessionHeaders(session, session.csrf())), JsonNode.class);
        assertEquals(HttpStatus.NO_CONTENT, out.getStatusCode());
        String cleared = out.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertNotNull(cleared);
        assertTrue(cleared.contains("Max-Age=0"));
        assertEquals(HttpStatus.UNAUTHORIZED, me(session).getStatusCode());
    }

    @Test
    void repeatedFailuresLockTheAccount() {
        Session admin = signInAdmin();
        JsonNode created = createUser(admin, unique("locked"), "OPERATOR");
        String username = created.get("user").get("username").asText();
        String password = created.get("oneTimePassword").asText();
        String secret = created.get("totpSecret").asText();

        for (int attempt = 0; attempt < 3; attempt++) {
            assertEquals(HttpStatus.UNAUTHORIZED,
                    login(username, password + "-wrong", totp(secret)).getStatusCode());
        }

        ResponseEntity<JsonNode> locked = login(username, password, totp(secret));
        assertEquals(HttpStatus.LOCKED, locked.getStatusCode());
        assertEquals("ACCOUNT_LOCKED", locked.getBody().get("code").asText());
        assertNotNull(locked.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
    }

    @Test
    void creationReturnsTheSecretsOnceAndReadsNeverDo() {
        Session admin = signInAdmin();
        String username = unique("once");
        JsonNode created = createUser(admin, username, "ANALYST");

        assertEquals(32, created.get("oneTimePassword").asText().length());
        assertFalse(created.get("totpSecret").asText().isBlank());

        ResponseEntity<JsonNode> listed = rest.exchange("/v1/ops/users", HttpMethod.GET,
                new HttpEntity<>(sessionHeaders(admin, null)), JsonNode.class);
        assertEquals(HttpStatus.OK, listed.getStatusCode());
        String json = listed.getBody().toString();
        assertFalse(json.contains(created.get("oneTimePassword").asText()));
        assertFalse(json.contains(created.get("totpSecret").asText()));
        assertFalse(json.contains("passwordHash"));
        assertFalse(json.contains("totpSecret"));
    }

    @Test
    void theLastAdministratorCannotBeRemoved() {
        Session admin = signInAdmin();
        UUID rootId = UUID.fromString(admin.body().get("userId").asText());

        ResponseEntity<JsonNode> disableSelf = rest.exchange("/v1/ops/users/" + rootId,
                HttpMethod.PATCH, new HttpEntity<>(Map.of("enabled", false),
                        sessionHeaders(admin, admin.csrf())), JsonNode.class);
        assertEquals(HttpStatus.CONFLICT, disableSelf.getStatusCode());
        assertEquals("SELF_DISABLE", disableSelf.getBody().get("code").asText());

        ResponseEntity<JsonNode> demoteSelf = rest.exchange("/v1/ops/users/" + rootId,
                HttpMethod.PATCH, new HttpEntity<>(Map.of("role", "OPERATOR"),
                        sessionHeaders(admin, admin.csrf())), JsonNode.class);
        assertEquals(HttpStatus.CONFLICT, demoteSelf.getStatusCode());
        assertEquals("LAST_ADMIN", demoteSelf.getBody().get("code").asText());
    }

    @Test
    void passwordsAreArgon2idAndSessionTokensAreStoredOnlyAsHashes() {
        Session session = signInAdmin();
        String hash = jdbc.queryForObject("SELECT password_hash FROM ops_user WHERE username = 'root'",
                String.class);
        assertNotNull(hash);
        assertTrue(hash.startsWith("$argon2id$v=19$m=4096,t=1,p=1$"));

        Long byHash = jdbc.queryForObject("SELECT count(*) FROM ops_session WHERE token_hash = ?",
                Long.class, OpsSessionService.sha256(session.token()));
        Long byRaw = jdbc.queryForObject("SELECT count(*) FROM ops_session WHERE token_hash = ?",
                Long.class, session.token());
        assertEquals(1L, byHash);
        assertEquals(0L, byRaw);
    }

    @Test
    void theTrailRecordsBothOutcomes() {
        login("root", ADMIN_PASSWORD, "000000");
        signInAdmin();

        Long allowed = jdbc.queryForObject("SELECT count(*) FROM audit_event"
                + " WHERE action = 'OPS_LOGIN' AND outcome = 'ALLOWED'", Long.class);
        Long denied = jdbc.queryForObject("SELECT count(*) FROM audit_event"
                + " WHERE action = 'OPS_LOGIN' AND outcome = 'DENIED'", Long.class);
        assertNotNull(allowed);
        assertNotNull(denied);
        assertTrue(allowed >= 1);
        assertTrue(denied >= 1);
    }

    // ---------------------------------------------------------------- helpers

    private Session signInAdmin() {
        return signIn("root", ADMIN_PASSWORD, ADMIN_TOTP);
    }

    private Session signIn(String username, String password, String secret) {
        ResponseEntity<JsonNode> response = login(username, password, totp(secret));
        assertEquals(HttpStatus.OK, response.getStatusCode(), String.valueOf(response.getBody()));
        String setCookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertNotNull(setCookie);
        String token = setCookie.substring("GMLC_OPS=".length(), setCookie.indexOf(';'));
        return new Session(token, response.getBody().get("csrfToken").asText(), response.getBody());
    }

    private ResponseEntity<JsonNode> login(String username, String password, String code) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("password", password);
        body.put("totp", code);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity("/v1/ops/session", new HttpEntity<>(body, headers), JsonNode.class);
    }

    private ResponseEntity<JsonNode> me(Session session) {
        return rest.exchange("/v1/ops/me", HttpMethod.GET,
                new HttpEntity<>(sessionHeaders(session, null)), JsonNode.class);
    }

    private JsonNode createUser(Session admin, String username, String role) {
        ResponseEntity<JsonNode> response = rest.exchange("/v1/ops/users", HttpMethod.POST,
                new HttpEntity<>(userBody(username, role), sessionHeaders(admin, admin.csrf())),
                JsonNode.class);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(), String.valueOf(response.getBody()));
        return response.getBody();
    }

    private static Map<String, Object> userBody(String username, String role) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("displayName", "Test " + username);
        body.put("role", role);
        return body;
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

    private static String totp(String secret) {
        return Totp.code(secret, Instant.now());
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private record Session(String token, String csrf, JsonNode body) {
    }
}
