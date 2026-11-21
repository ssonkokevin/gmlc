package com.hamiltel.gmlc;

import com.fasterxml.jackson.databind.JsonNode;
import com.hamiltel.gmlc.support.EmbeddedPostgresSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.client.RestTemplateBuilder;
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
import org.springframework.web.util.DefaultUriBuilderFactory;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cell database surface: a dry-run report by default, one transaction per
 * commit, canonical identities, derived geometry and a quality score. Writing
 * needs the operator key; reading needs an API key.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CellApiTest {

    private static final String HEADER = "rat,plmn_mcc,plmn_mnc,area_code,cell_code,site_name,"
            + "latitude,longitude,antenna_height_m,azimuth_deg,beamwidth_deg,radius_m,pci,arfcn,"
            + "cell_type,data_source,last_verified\n";

    private static final String TWO_CELLS = HEADER
            + "LTE,001,01,4660,1234567,\"Kampala, Central\",0.313611,32.581111,30,120,65,250,"
            + "101,6300,MACRO,\"RF plan, 2026\",2026-06-01\n"
            + "LTE,001,01,4660,1234568,Kampala East,0.335000,32.600000,25,,,500,102,6300,"
            + "MICRO,drive test,2026-05-01\n";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        EmbeddedPostgresSupport.register(registry);
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private RestTemplateBuilder restTemplateBuilder;

    @Autowired
    private JdbcTemplate jdbc;

    @LocalServerPort
    private int port;

    private String apiKey;

    @BeforeEach
    void cleanCells() {
        jdbc.update("delete from cell");
        apiKey = EmbeddedPostgresSupport.registerClient(rest, "cells", 1000, 100_000, null)
                .path("apiKey").asText();
    }

    private TestRestTemplate http() {
        TestRestTemplate client = new TestRestTemplate(
                restTemplateBuilder.requestFactory(settings -> new JdkClientHttpRequestFactory()));
        client.setUriTemplateHandler(new DefaultUriBuilderFactory("http://localhost:" + port));
        return client;
    }

    private ResponseEntity<JsonNode> importCsv(String csv, Boolean dryRun) {
        HttpHeaders headers = EmbeddedPostgresSupport.adminHeaders();
        headers.setContentType(new MediaType("text", "csv"));
        String path = dryRun == null ? "/v1/admin/cells/import"
                : "/v1/admin/cells/import?dryRun=" + dryRun;
        return http().exchange(path, HttpMethod.POST, new HttpEntity<>(csv, headers), JsonNode.class);
    }

    private ResponseEntity<JsonNode> upsertCell(Map<String, Object> body, String adminKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (adminKey != null) {
            headers.set("X-Admin-Key", adminKey);
        }
        return http().exchange("/v1/admin/cells", HttpMethod.POST,
                new HttpEntity<>(body, headers), JsonNode.class);
    }

    private static Map<String, Object> cellBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("rat", "lte");
        body.put("plmnMcc", "001");
        body.put("plmnMnc", "01");
        body.put("areaCode", "04660");
        body.put("cellCode", "1234567");
        body.put("siteName", "Kampala Central");
        body.put("latitude", 0.313611);
        body.put("longitude", 32.581111);
        body.put("antennaHeightM", 30.0);
        body.put("azimuthDeg", 120);
        body.put("beamwidthDeg", 65);
        body.put("radiusM", 250);
        body.put("pci", 101);
        body.put("arfcn", 6300);
        body.put("cellType", "macro");
        body.put("dataSource", "RF plan");
        body.put("lastVerified", "2026-06-01");
        return body;
    }

    @Test
    void anImportIsDryRunByDefault() {
        ResponseEntity<JsonNode> response = importCsv(TWO_CELLS, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode report = response.getBody();
        assertThat(report.path("dryRun").asBoolean()).isTrue();
        assertThat(report.path("totalRows").asInt()).isEqualTo(2);
        assertThat(report.path("validRows").asInt()).isEqualTo(2);
        assertThat(report.path("inserted").asInt()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from cell", Integer.class)).isZero();
    }

    @Test
    void aCommitWritesAndAReimportUpdates() {
        ResponseEntity<JsonNode> first = importCsv(TWO_CELLS, false);
        assertThat(first.getBody().path("inserted").asInt()).isEqualTo(2);
        assertThat(first.getBody().path("updated").asInt()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from cell", Integer.class)).isEqualTo(2);

        ResponseEntity<JsonNode> second = importCsv(TWO_CELLS, false);
        assertThat(second.getBody().path("inserted").asInt()).isZero();
        assertThat(second.getBody().path("updated").asInt()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from cell", Integer.class)).isEqualTo(2);

        assertThat(jdbc.queryForObject("select data_quality from cell"
                + " where cell_identity = '001-01-4660-1234567'", Integer.class)).isEqualTo(100);
        assertThat(jdbc.queryForObject("select coverage_polygon is not null from cell"
                + " where cell_identity = '001-01-4660-1234567'", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("select coverage_polygon is not null from cell"
                + " where cell_identity = '001-01-4660-1234568'", Boolean.class)).isTrue();
    }

    @Test
    void badRowsAreReportedWithTheirLineAndFieldWhileGoodRowsImport() {
        String csv = HEADER
                + "LTE,001,01,4660,999,Good Cell,0.300000,32.500000,20,,,300,10,6300,MACRO,plan,2026-05-01\n"
                + "LTE,001,01,4660,998,Bad Latitude,120.000000,32.500000,20,,,300,10,6300,MACRO,plan,2026-05-01\n"
                + "LTE,001,01,4660,997,No Radius,0.300000,32.500000,20,,,,10,6300,MACRO,plan,2026-05-01\n"
                + "LTE,001,01,4660,999,Duplicate,0.300000,32.500000,20,,,300,10,6300,MACRO,plan,2026-05-01\n";

        ResponseEntity<JsonNode> response = importCsv(csv, false);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode report = response.getBody();
        assertThat(report.path("totalRows").asInt()).isEqualTo(4);
        assertThat(report.path("validRows").asInt()).isEqualTo(1);
        assertThat(report.path("invalidRows").asInt()).isEqualTo(3);
        assertThat(report.path("inserted").asInt()).isEqualTo(1);

        String errors = report.path("errors").toString();
        assertThat(errors).contains("\"line\":3").contains("\"field\":\"latitude\"");
        assertThat(errors).contains("\"line\":4").contains("\"field\":\"radiusM\"");
        assertThat(errors).contains("\"line\":5").contains("duplicates the cell on line 2");
        assertThat(jdbc.queryForObject("select count(*) from cell", Integer.class)).isEqualTo(1);
    }

    @Test
    void aFileWithoutRequiredColumnsIsRefused() {
        String csv = "rat,plmn_mcc,plmn_mnc,area_code,cell_code,latitude,radius_m\n"
                + "LTE,001,01,4660,1234567,0.313611,250\n";

        ResponseEntity<JsonNode> response = importCsv(csv, false);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().path("code").asText()).isEqualTo("CSV_MISSING_COLUMNS");
        assertThat(response.getBody().path("detail").asText()).contains("longitude");
        assertThat(jdbc.queryForObject("select count(*) from cell", Integer.class)).isZero();
    }

    @Test
    void writingACellNeedsTheAdminKey() {
        ResponseEntity<JsonNode> response = upsertCell(cellBody(), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().path("title").asText()).isEqualTo("Unauthorised");
        assertThat(jdbc.queryForObject("select count(*) from cell", Integer.class)).isZero();
    }

    @Test
    void aSingleCellIsUpsertedCanonicallyAndReadBack() {
        ResponseEntity<JsonNode> created = upsertCell(cellBody(),
                EmbeddedPostgresSupport.ADMIN_KEY);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode cell = created.getBody();
        assertThat(cell.path("rat").asText()).isEqualTo("LTE");
        assertThat(cell.path("cellIdentity").asText()).isEqualTo("001-01-4660-1234567");
        assertThat(cell.path("areaCode").asText()).isEqualTo("4660");
        assertThat(cell.path("plmn").asText()).isEqualTo("001-01");
        assertThat(cell.path("shape").asText()).isEqualTo("ARC");
        assertThat(cell.path("coveragePolygon").path("type").asText()).isEqualTo("Polygon");
        assertThat(cell.path("cellType").asText()).isEqualTo("MACRO");
        assertThat(cell.path("dataQuality").asInt()).isEqualTo(100);

        ResponseEntity<JsonNode> updated = upsertCell(cellBody(),
                EmbeddedPostgresSupport.ADMIN_KEY);
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<JsonNode> fetched = EmbeddedPostgresSupport.get(rest,
                "/v1/cells/LTE/001-01-4660-1234567", apiKey);
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody().path("cellIdentity").asText())
                .isEqualTo("001-01-4660-1234567");

        ResponseEntity<JsonNode> unpadded = EmbeddedPostgresSupport.get(rest,
                "/v1/cells/LTE/001-01-04660-1234567", apiKey);
        assertThat(unpadded.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<JsonNode> page = EmbeddedPostgresSupport.get(rest,
                "/v1/cells?rat=LTE&area=4660", apiKey);
        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(page.getBody().path("total").asLong()).isEqualTo(1);
        assertThat(page.getBody().path("cells").get(0).path("shape").asText()).isEqualTo("ARC");
        assertThat(page.getBody().path("cells").get(0).has("coveragePolygon")).isFalse();
    }

    @Test
    void aCellWithoutARadiusIsRefused() {
        Map<String, Object> body = cellBody();
        body.remove("radiusM");

        ResponseEntity<JsonNode> response = upsertCell(body, EmbeddedPostgresSupport.ADMIN_KEY);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().path("title").asText()).isEqualTo("Invalid cell");
        assertThat(response.getBody().path("fields").has("radiusM")).isTrue();
        assertThat(response.getBody().path("fields").has("latitude")).isFalse();
    }

    @Test
    void invalidFiltersAndUnknownCellsAreRefusedClearly() {
        ResponseEntity<JsonNode> badRat = EmbeddedPostgresSupport.get(rest,
                "/v1/cells?rat=WIFI", apiKey);
        assertThat(badRat.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(badRat.getBody().path("code").asText()).isEqualTo("INVALID_RAT");

        ResponseEntity<JsonNode> missing = EmbeddedPostgresSupport.get(rest,
                "/v1/cells/LTE/001-01-4660-1", apiKey);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(missing.getBody().path("cellIdentity").asText()).isEqualTo("001-01-4660-1");
    }
}
