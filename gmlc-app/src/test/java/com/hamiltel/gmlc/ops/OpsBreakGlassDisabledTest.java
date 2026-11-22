package com.hamiltel.gmlc.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.hamiltel.gmlc.support.EmbeddedPostgresSupport;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * A deployment without a sealed code has break-glass switched off, and says so
 * on the policy page rather than leaving a button that cannot work.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "gmlc.ops.cookie-secure=false",
        "gmlc.ops.argon2-memory-kib=4096",
        "gmlc.ops.argon2-iterations=1",
        "gmlc.ops.bootstrap-username=root",
        "gmlc.ops.bootstrap-password=" + OpsBreakGlassDisabledTest.ADMIN_PASSWORD,
        "gmlc.ops.bootstrap-totp-secret=" + OpsBreakGlassDisabledTest.ADMIN_TOTP,
        "gmlc.ops.alerts.enabled=false",
        "gmlc.webhooks.enabled=false",
        "gmlc.webhooks.initial-delay-seconds=3600",
        "gmlc.tracking.initial-delay-seconds=3600"
})
class OpsBreakGlassDisabledTest {

    static final String ADMIN_PASSWORD = "bootstrap-password-123";
    static final String ADMIN_TOTP = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP";

    @Autowired
    TestRestTemplate rest;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        EmbeddedPostgresSupport.register(registry);
    }

    @Test
    void breakGlassIsRefusedWhenNoCodeIsConfigured() {
        rest.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
        Session root = signInAdmin();

        ResponseEntity<JsonNode> policy = get(root, "/v1/ops/access/policy");
        assertEquals(HttpStatus.OK, policy.getStatusCode());
        assertFalse(policy.getBody().get("breakGlassConfigured").asBoolean());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reason", "a perfectly reasonable emergency reason");
        body.put("code", "any-code-at-all");
        ResponseEntity<JsonNode> refused = rest.exchange("/v1/ops/access/break-glass",
                org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(body, sessionHeaders(root, root.csrf())), JsonNode.class);
        assertEquals(HttpStatus.CONFLICT, refused.getStatusCode());
        assertEquals("BREAK_GLASS_DISABLED", refused.getBody().get("code").asText());
    }

    private ResponseEntity<JsonNode> get(Session session, String path) {
        return rest.exchange(path, org.springframework.http.HttpMethod.GET,
                new HttpEntity<>(sessionHeaders(session, null)), JsonNode.class);
    }

    private Session signInAdmin() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", "root");
        body.put("password", ADMIN_PASSWORD);
        body.put("totp", Totp.code(ADMIN_TOTP, Instant.now()));
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
