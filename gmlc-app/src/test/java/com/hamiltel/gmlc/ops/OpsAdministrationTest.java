package com.hamiltel.gmlc.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.hamiltel.gmlc.cell.CellIdentity;
import com.hamiltel.gmlc.cell.CellInput;
import com.hamiltel.gmlc.cell.CellRepository;
import com.hamiltel.gmlc.config.GmlcProperties;
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

import java.math.BigDecimal;
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
 * The administration reads: the cell plan summary an operator with
 * MANAGE_CONFIG may see, including its completeness bands, and the refusal a
 * role without that permission receives.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "gmlc.ops.cookie-secure=false",
        "gmlc.ops.argon2-memory-kib=4096",
        "gmlc.ops.argon2-iterations=1",
        "gmlc.ops.bootstrap-username=root",
        "gmlc.ops.bootstrap-password=" + OpsAdministrationTest.ADMIN_PASSWORD,
        "gmlc.ops.bootstrap-totp-secret=" + OpsAdministrationTest.ADMIN_TOTP,
        "gmlc.ops.alerts.enabled=false",
        "gmlc.webhooks.enabled=false",
        "gmlc.webhooks.initial-delay-seconds=3600",
        "gmlc.tracking.initial-delay-seconds=3600"
})
class OpsAdministrationTest {

    static final String ADMIN_PASSWORD = "bootstrap-password-123";
    static final String ADMIN_TOTP = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP";

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    CellRepository cells;

    @Autowired
    GmlcProperties properties;

    private final List<UUID> createdUsers = new ArrayList<>();
    private final List<String> createdCells = new ArrayList<>();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        EmbeddedPostgresSupport.register(registry);
    }

    @BeforeEach
    void reset() {
        rest.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
    }

    @AfterEach
    void cleanUp() {
        for (String identity : createdCells) {
            jdbc.update("DELETE FROM cell WHERE tenant_id = ? AND rat = 'NR' AND cell_identity = ?",
                    properties.defaultTenantId(), identity);
        }
        createdCells.clear();
        for (UUID userId : createdUsers) {
            jdbc.update("DELETE FROM ops_session WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM ops_user WHERE user_id = ?", userId);
        }
        createdUsers.clear();
    }

    @Test
    void theCellPlanIsVisibleWithItsCompletenessBands() {
        String cellCode = String.valueOf(30000 + (int) (Math.random() * 5000));
        String identity = CellIdentity.compose(CellIdentity.Rat.NR, "999", "70", "TST", cellCode);
        CellInput input = new CellInput("NR", "999", "70", "TST", cellCode, "Test site",
                new BigDecimal("0.3476"), new BigDecimal("32.5825"), new BigDecimal("25"),
                90, 65, 800, 12, 632448, "MACRO", "test-plan", 80, null);
        cells.upsert(properties.defaultTenantId(), CellIdentity.Rat.NR, identity, input, null, 80);
        createdCells.add(identity);

        Session root = signInAdmin();
        ResponseEntity<JsonNode> response = get(root, "/v1/ops/administration/cells?rat=NR&limit=200");
        assertEquals(HttpStatus.OK, response.getStatusCode(), String.valueOf(response.getBody()));
        JsonNode body = response.getBody();
        assertNotNull(body);
        assertTrue(body.get("total").asLong() >= 1);
        assertEquals("NR", body.get("rat").asText());
        assertTrue(body.get("byRat").toString().contains("\"name\":\"NR\""));
        assertTrue(body.get("byQuality").toString().contains("70-89"));
        boolean found = false;
        for (JsonNode cell : body.get("cells")) {
            if (identity.equals(cell.get("cellIdentity").asText())) {
                found = true;
                assertEquals("Test site", cell.get("siteName").asText());
                assertEquals(80, cell.get("dataQuality").asInt());
                assertEquals(0, new BigDecimal(cell.get("latitude").asText())
                        .compareTo(new BigDecimal("0.3476")));
            }
        }
        assertTrue(found, "the imported cell is on the page");
    }

    @Test
    void aRoleWithoutManageConfigCannotReadThePlan() {
        Session root = signInAdmin();
        Session viewer = signInAsNewUser(root, "VIEWER");

        ResponseEntity<JsonNode> refused = get(viewer, "/v1/ops/administration/cells");
        assertEquals(HttpStatus.FORBIDDEN, refused.getStatusCode());
        assertEquals("PERMISSION_DENIED", refused.getBody().get("code").asText());

        ResponseEntity<JsonNode> invalid = get(root, "/v1/ops/administration/cells?rat=WIFI");
        assertEquals(HttpStatus.BAD_REQUEST, invalid.getStatusCode());
        assertEquals("INVALID_RAT", invalid.getBody().get("code").asText());

        ResponseEntity<JsonNode> limit = get(root, "/v1/ops/administration/cells?limit=0");
        assertEquals(HttpStatus.BAD_REQUEST, limit.getStatusCode());
        assertEquals("LIMIT_OUT_OF_RANGE", limit.getBody().get("code").asText());
        assertFalse(limit.getBody().has("cells"));
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
        createdUsers.add(UUID.fromString(created.getBody().get("user").get("userId").asText()));
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
        return new Session(token, response.getBody().get("csrfToken").asText());
    }

    private ResponseEntity<JsonNode> get(Session session, String path) {
        return rest.exchange(path, HttpMethod.GET,
                new HttpEntity<>(sessionHeaders(session, null)), JsonNode.class);
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

    private record Session(String token, String csrf) {
    }
}
