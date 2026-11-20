package com.hamiltel.gmlc.client;

import com.hamiltel.gmlc.audit.AuditTrail;
import com.hamiltel.gmlc.config.GmlcProperties;
import com.hamiltel.gmlc.web.ProblemWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

/**
 * Every /v1 endpoint except health and the admin surface needs an API key and
 * is charged against that client's quota. The filter answers by itself rather
 * than delegating to MVC, so an unauthenticated request never reaches a
 * controller or a database query the client is not entitled to run.
 */
@Component
public class ApiKeyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyFilter.class);

    private final ClientAuthenticator authenticator;
    private final QuotaService quotas;
    private final AuditTrail audit;
    private final ProblemWriter problems;
    private final GmlcProperties properties;

    public ApiKeyFilter(ClientAuthenticator authenticator, QuotaService quotas,
                        AuditTrail audit, ProblemWriter problems, GmlcProperties properties) {
        this.authenticator = authenticator;
        this.quotas = quotas;
        this.audit = audit;
        this.problems = problems;
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return "OPTIONS".equalsIgnoreCase(request.getMethod())
                || !path.startsWith("/v1/")
                || path.equals("/v1/health")
                || path.equals("/v1/admin")
                || path.startsWith("/v1/admin/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            ApiClient client = authenticator.authenticate(apiKey(request), request.getRemoteAddr());

            QuotaService.Decision quota = quotas.consume(client);
            if (!quota.allowed()) {
                audit.record(client.tenantId(), client.clientId(), "QUOTA_EXCEEDED", null,
                        AuditTrail.Outcome.DENIED, Map.of("limit", quota.limit()));
                response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(quota.retryAfterSeconds()));
                problems.write(response, HttpStatus.TOO_MANY_REQUESTS, "Quota exceeded",
                        "the client has reached its " + quota.limit() + " allowance",
                        Map.of("limit", quota.limit()));
                return;
            }

            request.setAttribute(ClientContext.ATTRIBUTE, client);
            chain.doFilter(request, response);
        } catch (ClientAuthenticationException e) {
            // An unauthenticated request has no client, and therefore no tenant.
            // The gateway records it against the operating tenant so the trail
            // is complete; the audit row holds no credential material.
            audit.record(properties.defaultTenantId(), null, "AUTH_DENIED", null,
                    AuditTrail.Outcome.DENIED, Map.of("reason", e.reason().name()));
            boolean forbidden = e.reason() == ClientAuthenticationException.Reason.ADDRESS_NOT_ALLOWED;
            problems.write(response,
                    forbidden ? HttpStatus.FORBIDDEN : HttpStatus.UNAUTHORIZED,
                    forbidden ? "Forbidden" : "Unauthorised",
                    forbidden ? "this client address is not allowed"
                              : "a valid API key is required",
                    Map.of());
        }
    }

    private static String apiKey(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return header.substring(7).trim();
        }
        return request.getHeader("X-API-Key");
    }
}
