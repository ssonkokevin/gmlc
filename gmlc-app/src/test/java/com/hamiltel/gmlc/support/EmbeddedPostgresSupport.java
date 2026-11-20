package com.hamiltel.gmlc.support;

import com.fasterxml.jackson.databind.JsonNode;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One embedded PostgreSQL for the whole test run, shared by every Spring
 * context, closed by a JVM shutdown hook. The admin key is a constant so that
 * two test classes pointing at the same database agree on it.
 */
public final class EmbeddedPostgresSupport {

    public static final String ADMIN_KEY = "test-admin-key-not-a-secret";

    private static final EmbeddedPostgres POSTGRES;
    private static final String URL;

    static {
        try {
            POSTGRES = EmbeddedPostgres.builder().start();
            URL = POSTGRES.getJdbcUrl("postgres", "postgres");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    POSTGRES.close();
                } catch (IOException ignored) {
                    // The process is going away anyway.
                }
            }));
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private EmbeddedPostgresSupport() {
    }

    public static void register(DynamicPropertyRegistry registry) {
        registerDatasource(registry);
        registry.add("gmlc.admin-key", () -> ADMIN_KEY);
    }

    /** For contexts that deliberately run without an admin key. */
    public static void registerDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> URL);
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
    }

    public static JsonNode registerClient(TestRestTemplate rest, String namePrefix) {
        return registerClient(rest, namePrefix, null, null, null);
    }

    public static JsonNode registerClient(TestRestTemplate rest, String namePrefix,
                                          Integer ratePerSecond, Integer ratePerDay,
                                          List<String> ipAllowlist) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", namePrefix + "-" + UUID.randomUUID().toString().substring(0, 8));
        body.put("purposes", List.of("TEST"));
        if (ratePerSecond != null) {
            body.put("ratePerSecond", ratePerSecond);
        }
        if (ratePerDay != null) {
            body.put("ratePerDay", ratePerDay);
        }
        if (ipAllowlist != null) {
            body.put("ipAllowlist", ipAllowlist);
        }
        ResponseEntity<JsonNode> response = rest.exchange("/v1/admin/clients", HttpMethod.POST,
                new HttpEntity<>(body, adminHeaders()), JsonNode.class);
        if (response.getStatusCode() != HttpStatus.CREATED || response.getBody() == null) {
            throw new IllegalStateException("client registration failed: " + response.getStatusCode()
                    + " " + response.getBody());
        }
        return response.getBody();
    }

    public static HttpHeaders adminHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Admin-Key", ADMIN_KEY);
        return headers;
    }

    public static HttpHeaders bearer(String apiKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(apiKey);
        return headers;
    }

    public static ResponseEntity<JsonNode> get(TestRestTemplate rest, String path, String apiKey) {
        return rest.exchange(path, HttpMethod.GET,
                new HttpEntity<>(apiKey == null ? new HttpHeaders() : bearer(apiKey)), JsonNode.class);
    }
}
