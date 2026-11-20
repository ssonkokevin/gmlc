package com.hamiltel.gmlc;

import com.fasterxml.jackson.databind.JsonNode;
import com.hamiltel.gmlc.capability.CapabilityProfileUnavailableException;
import com.hamiltel.gmlc.capability.CapabilityService;
import com.hamiltel.gmlc.support.EmbeddedPostgresSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The skeleton's contract: both migrations apply to a real PostgreSQL, the
 * health endpoint tells the truth, the capability profile is served to an
 * authenticated client, and a stored profile that breaks the rules stops the
 * API rather than being served.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GmlcApiTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        EmbeddedPostgresSupport.register(registry);
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CapabilityService capabilities;

    private String apiKey;

    @BeforeEach
    void registerClient() {
        apiKey = EmbeddedPostgresSupport.registerClient(rest, "api", 1000, 100_000, null)
                .path("apiKey").asText();
    }

    @Test
    void migrationsApplyAndCreateTheLocationTables() {
        Integer applied = jdbc.queryForObject(
                "select count(*) from flyway_schema_history where success", Integer.class);
        assertThat(applied).isEqualTo(2);
        String version = jdbc.queryForObject(
                "select version from flyway_schema_history where success order by installed_rank desc limit 1",
                String.class);
        assertThat(version).isEqualTo("2");

        Integer tables = jdbc.queryForObject(
                "select count(*) from information_schema.tables where table_schema = 'public'"
                        + " and table_name in ('tenant', 'capability_profile', 'api_client', 'client_usage',"
                        + " 'consent', 'cell', 'location_request', 'location_result', 'cdr', 'audit_event')",
                Integer.class);
        assertThat(tables).isEqualTo(10);
    }

    @Test
    void tenantOneIsSeeded() {
        String name = jdbc.queryForObject("select name from tenant where tenant_id = 1", String.class);
        assertThat(name).isEqualTo("Hamiltel");
    }

    @Test
    void healthReportsUpWithTheBundledProfile() {
        ResponseEntity<JsonNode> response = rest.getForEntity("/v1/health", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.path("status").asText()).isEqualTo("UP");
        assertThat(body.path("database").asText()).isEqualTo("UP");
        assertThat(body.path("capabilities").asText()).isEqualTo("UP");
        assertThat(body.path("capabilitySource").asText()).isEqualTo("bundled");
        assertThat(body.path("capabilityProfileVersion").asText()).isEqualTo("1.0.0");
    }

    @Test
    void capabilitiesReturnsTheTenantProfileVerbatim() {
        ResponseEntity<JsonNode> response =
                EmbeddedPostgresSupport.get(rest, "/v1/capabilities", apiKey);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst("X-Capability-Source")).isEqualTo("bundled");
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.path("tenant_id").asLong()).isEqualTo(1);
        assertThat(body.path("version").asText()).isEqualTo("1.0.0");
        assertThat(body.path("rat").path("LTE").path("method_status").path("A_GNSS").asText())
                .isEqualTo("PLANNED");
        assertThat(body.path("rat").path("NR").path("engine").asText()).isEqualTo("LMF");
    }

    @Test
    void aStoredProfileOverridesTheBundledOne() {
        String stored = """
                {"tenant_id":1,"version":"9.9.9","rat":{
                  "GSM":{"serving_node":"MSC/SGSN","engine":null,"methods":["CELL_ID"],
                         "method_status":{"CELL_ID":"BUILT"}},
                  "UMTS":{"serving_node":"MSC/SGSN","engine":null,"methods":["CELL_ID"],
                          "method_status":{"CELL_ID":"BUILT"}},
                  "LTE":{"serving_node":"MME","engine":"E_SMLC","methods":["CELL_ID"],
                         "method_status":{"CELL_ID":"BUILT"}},
                  "NR":{"serving_node":"AMF","engine":"LMF","methods":["CELL_ID"],
                        "method_status":{"CELL_ID":"BUILT"}}},
                 "ati_allowed":false,"psi_allowed":false,"active_location_paging":false,
                 "plmns":[{"mcc":"001","mnc":"01"}]}
                """;
        jdbc.update("insert into capability_profile (tenant_id, version, profile)"
                + " values (1, ?, ?::jsonb)", "9.9.9", stored);

        CapabilityService.Snapshot snapshot = capabilities.current(1);

        assertThat(snapshot.source()).isEqualTo("database");
        assertThat(snapshot.profile().path("version").asText()).isEqualTo("9.9.9");
        assertThat(snapshot.profile().path("ati_allowed").asBoolean()).isFalse();

        ResponseEntity<JsonNode> response =
                EmbeddedPostgresSupport.get(rest, "/v1/capabilities", apiKey);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().path("version").asText()).isEqualTo("9.9.9");

        jdbc.update("delete from capability_profile");
    }

    @Test
    void capabilitiesNeedsAnApiKey() {
        ResponseEntity<JsonNode> response = rest.getForEntity("/v1/capabilities", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("title").asText()).isEqualTo("Unauthorised");
        assertThat(response.getBody().path("detail").asText()).contains("API key");
    }

    @Test
    void aStoredProfileThatBreaksTheRulesIsNotServed() {
        jdbc.update("insert into capability_profile (tenant_id, version, profile)"
                + " values (1, '1.0.1', ?::jsonb)", "{\"tenant_id\":1,\"version\":\"1.0.1\"}");

        assertThatThrownBy(() -> capabilities.current(1))
                .isInstanceOf(CapabilityProfileUnavailableException.class)
                .hasMessageContaining("invalid");

        ResponseEntity<JsonNode> response =
                EmbeddedPostgresSupport.get(rest, "/v1/capabilities", apiKey);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().path("title").asText())
                .isEqualTo("Capability profile unavailable");

        jdbc.update("delete from capability_profile");
    }

    @Test
    void anUnknownTenantHasNoCapabilities() {
        assertThatThrownBy(() -> capabilities.current(42))
                .isInstanceOf(com.hamiltel.gmlc.capability.UnknownTenantException.class);
    }

    @Test
    void aRequestWithoutASubjectIsRejectedByTheSchema() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        jdbc.update("insert into location_request"
                                + " (request_id, tenant_id, client_id, purpose, state)"
                                + " values (gen_random_uuid(), 1, gen_random_uuid(), 'TEST', 'RECEIVED')"))
                .hasMessageContaining("request_has_a_subject");
    }
}
