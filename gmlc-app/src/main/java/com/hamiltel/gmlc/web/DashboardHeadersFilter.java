package com.hamiltel.gmlc.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Security headers for the static pages: the client dashboard and the operator
 * console. Neither embeds a credential in markup and neither may call a third
 * party, so the policy allows only this origin, no inline script or style, no
 * framing and no referrer. The /v1 endpoints keep the headers they already
 * send.
 */
@Component
public class DashboardHeadersFilter extends OncePerRequestFilter {

    public static final String POLICY = "default-src 'none'; script-src 'self'; "
            + "style-src 'self'; img-src 'self' data:; connect-src 'self'; "
            + "form-action 'none'; base-uri 'none'; frame-ancestors 'none'";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !guarded(request.getRequestURI());
    }

    static boolean guarded(String uri) {
        return uri.equals("/") || uri.equals("/index.html")
                || uri.equals("/dashboard.js") || uri.equals("/dashboard.css")
                || uri.equals("/console") || uri.equals("/console/")
                || uri.equals("/console/index.html")
                || uri.startsWith("/console/assets/");
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
