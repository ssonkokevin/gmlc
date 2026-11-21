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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The operator console is a built React bundle served as static files under
 * /console/. These tests cover what can be checked without a browser: it is
 * served, its entry point loads only bundled same-origin assets, it carries the
 * same strict content-security policy as the older dashboard, and it does not
 * open the operations API.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConsoleTest {

    private static final Pattern SCRIPT = Pattern.compile("src=\"(/console/assets/[^\"]+\\.js)\"");
    private static final Pattern STYLE = Pattern.compile("href=\"(/console/assets/[^\"]+\\.css)\"");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        EmbeddedPostgresSupport.register(registry);
    }

    @Autowired
    private TestRestTemplate rest;

    @Test
    void theConsoleIsServedAtItsAddressAsHtml() {
        for (String path : new String[]{"/console", "/console/"}) {
            ResponseEntity<String> page = rest.getForEntity(path, String.class);

            assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(page.getHeaders().getContentType()).isNotNull();
            assertThat(page.getHeaders().getContentType().isCompatibleWith(MediaType.TEXT_HTML)).isTrue();
            assertThat(page.getBody())
                    .contains("<div id=\"root\">")
                    .doesNotContain("http://")
                    .doesNotContain("https://");
        }
    }

    @Test
    void theEntryPointLoadsOnlyBundledSameOriginAssets() {
        ResponseEntity<String> page = rest.getForEntity("/console/", String.class);

        String body = page.getBody();
        assertThat(body).isNotNull();
        // External files only: an inline script would be refused by the policy.
        assertThat(scriptTagsWithoutSrc(body)).isZero();
        assertThat(body).doesNotContain("style=").doesNotContain("<style");

        Matcher script = SCRIPT.matcher(body);
        Matcher style = STYLE.matcher(body);
        assertThat(script.find()).isTrue();
        assertThat(style.find()).isTrue();

        ResponseEntity<String> scriptBody = rest.getForEntity(script.group(1), String.class);
        assertThat(scriptBody.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(scriptBody.getHeaders().getContentType()).isNotNull();
        assertThat(scriptBody.getHeaders().getContentType().toString()).contains("javascript");
        assertThat(scriptBody.getBody()).contains("/v1/ops/");

        ResponseEntity<String> styleBody = rest.getForEntity(style.group(1), String.class);
        assertThat(styleBody.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(styleBody.getHeaders().getContentType()).isNotNull();
        assertThat(styleBody.getHeaders().getContentType().toString()).contains("css");
    }

    @Test
    void theConsoleCarriesTheStrictPolicyOnThePageAndItsAssets() {
        ResponseEntity<String> page = rest.getForEntity("/console/", String.class);
        assertThat(page.getHeaders().getFirst("Content-Security-Policy"))
                .isEqualTo(DashboardHeadersFilter.POLICY)
                .doesNotContain("unsafe-inline")
                .contains("script-src 'self'");
        assertThat(page.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(page.getHeaders().getFirst("Referrer-Policy")).isEqualTo("no-referrer");

        Matcher script = SCRIPT.matcher(page.getBody());
        assertThat(script.find()).isTrue();
        ResponseEntity<String> asset = rest.getForEntity(script.group(1), String.class);
        assertThat(asset.getHeaders().getFirst("Content-Security-Policy"))
                .isEqualTo(DashboardHeadersFilter.POLICY);
    }

    @Test
    void servingTheConsoleDoesNotOpenTheOperationsApi() {
        assertThat(rest.getForEntity("/v1/ops/me", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rest.getForEntity("/v1/ops/summary?range=24h", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rest.getForEntity("/v1/ops/alerts", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private static long scriptTagsWithoutSrc(String html) {
        return Pattern.compile("<script(?![^>]*\\bsrc=)[^>]*>").matcher(html).results().count();
    }
}
