package com.hamiltel.gmlc.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * Security headers for the three static dashboard files. The page carries an
 * API key, so it is same-origin only: no inline script or style, no external
 * fetch, no framing and no referrer. The /v1 endpoints are untouched and keep
 * whatever headers they already send.
 */
@Component
public class DashboardHeadersFilter extends OncePerRequestFilter {

    public static final String POLICY = "default-src 'none'; script-src 'self'; "
            + "style-src 'self'; img-src 'self' data:; connect-src 'self'; "
            + "form-action 'none'; base-uri 'none'; frame-ancestors 'none'";

    private static final Set<String> DASHBOARD_PATHS =
            Set.of("/", "/index.html", "/dashboard.js", "/dashboard.css");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !DASHBOARD_PATHS.contains(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        response.setHeader("Content-Security-Policy", POLICY);
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Cache-Control", "no-store");
        chain.doFilter(request, response);
    }
}
