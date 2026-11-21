package com.hamiltel.gmlc;

import com.hamiltel.gmlc.support.EmbeddedPostgresSupport;
import com.hamiltel.gmlc.web.DashboardHeadersFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The dashboard is three static files served by the gateway. These tests cover
 * what can be checked without a browser: it is served unauthenticated, it
 * references only this origin and only the documented endpoints, it carries a
 * strict content-security policy, and serving it does not open the API.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DashboardTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        EmbeddedPostgresSupport.register(registry);
    }

    @Autowired
    private TestRestTemplate rest;

    @Test
    void theDashboardIsServedWithoutAKey() {
        ResponseEntity<String> page = rest.getForEntity("/", String.class);

        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(page.getHeaders().getContentType()).isNotNull();
        assertThat(page.getHeaders().getContentType().isCompatibleWith(MediaType.TEXT_HTML)).isTrue();
        assertThat(page.getBody())
                .contains("id=\"session-form\"")
                .contains("id=\"locate-form\"")
                .contains("id=\"result\"")
                .contains("id=\"log\"")
                .contains("/dashboard.js")
                .contains("/dashboard.css");
        // No CDN, tile server, font or other third-party origin.
        assertThat(page.getBody()).doesNotContain("http://").doesNotContain("https://");
    }

    @Test
    void theScriptCallsOnlyTheDocumentedEndpointsOnThisOrigin() {
        ResponseEntity<String> script = rest.getForEntity("/dashboard.js", String.class);

        assertThat(script.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(script.getHeaders().getContentType()).isNotNull();
        assertThat(script.getHeaders().getContentType().toString()).contains("javascript");
        assertThat(script.getBody())
                .contains("/v1/clients/me")
                .contains("/v1/consents")
                .contains("/v1/locations")
                .contains("/v1/cdrs")
                .contains("/v1/cells");
        // The only absolute URI is the SVG namespace; nothing is fetched from it.
        String withoutSvgNamespace = script.getBody()
                .replace("http://www.w3.org/2000/svg", "");
        assertThat(withoutSvgNamespace).doesNotContain("http://").doesNotContain("https://");
        assertThat(script.getBody()).contains("sessionStorage");
    }

    @Test
    void theDashboardCarriesAStrictContentSecurityPolicy() {
        ResponseEntity<String> page = rest.getForEntity("/", String.class);

        assertThat(page.getHeaders().getFirst("Content-Security-Policy"))
                .isEqualTo(DashboardHeadersFilter.POLICY)
                .doesNotContain("unsafe-inline")
                .contains("script-src 'self'")
                .contains("frame-ancestors 'none'");
        assertThat(page.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(page.getHeaders().getFirst("Referrer-Policy")).isEqualTo("no-referrer");
    }

    @Test
    void servingTheDashboardDoesNotOpenTheApi() {
        assertThat(rest.getForEntity("/v1/cells", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rest.getForEntity("/v1/cdrs", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rest.getForEntity("/v1/clients/me", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
