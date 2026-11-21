package com.hamiltel.gmlc.ops;

import com.hamiltel.gmlc.web.ProblemWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The console's front door. It runs only on /v1/ops: a request there is
 * authenticated by the session cookie and nothing else - an API key in the
 * Authorization header is ignored, so a client credential cannot call the
 * operator surface. State-changing methods must echo the session's CSRF token.
 *
 * <p>The login call is the one exception: it creates the session, so it cannot
 * require one.
 */
@Component
public class OpsSessionFilter extends OncePerRequestFilter {

    public static final String COOKIE_NAME = "GMLC_OPS";
    public static final String COOKIE_PATH = "/v1/ops";
    public static final String CSRF_HEADER = "X-CSRF-Token";

    private static final Set<String> UNSAFE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final String LOGIN_PATH = "/v1/ops/session";

    private final OpsSessionService sessions;
    private final ProblemWriter problems;

    public OpsSessionFilter(OpsSessionService sessions, ProblemWriter problems) {
        this.sessions = sessions;
        this.problems = problems;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !isOpsPath(request.getRequestURI()) || "OPTIONS".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "no-referrer");

        boolean login = LOGIN_PATH.equals(request.getRequestURI())
                && "POST".equalsIgnoreCase(request.getMethod());
        if (login) {
            chain.doFilter(request, response);
            return;
        }

        Optional<OpsContext> context = sessions.authenticate(readCookie(request));
        if (context.isEmpty()) {
            problems.write(response, HttpStatus.UNAUTHORIZED, "Unauthorised",
                    "an operator session is required", Map.of("code", "OPS_SESSION_REQUIRED"));
            return;
        }

        if (UNSAFE_METHODS.contains(request.getMethod().toUpperCase())
                && !csrfMatches(request.getHeader(CSRF_HEADER), context.get().csrfToken())) {
            problems.write(response, HttpStatus.FORBIDDEN, "Forbidden",
                    "the request is missing a valid CSRF token",
                    Map.of("code", "CSRF_REQUIRED"));
            return;
        }

        request.setAttribute(OpsContext.ATTRIBUTE, context.get());
        chain.doFilter(request, response);
    }

    public static boolean isOpsPath(String path) {
        return path != null && (path.equals("/v1/ops") || path.startsWith("/v1/ops/"));
    }

    private static String readCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (COOKIE_NAME.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private static boolean csrfMatches(String header, String expected) {
        if (header == null || expected == null) {
            return false;
        }
        return MessageDigest.isEqual(header.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }

    static String cookieHeader(String token, long maxAgeSeconds, boolean secure) {
        StringBuilder header = new StringBuilder()
                .append(COOKIE_NAME).append('=').append(token == null ? "" : token)
                .append("; Path=").append(COOKIE_PATH)
                .append("; HttpOnly; SameSite=Strict")
                .append("; Max-Age=").append(maxAgeSeconds);
        if (secure) {
            header.append("; Secure");
        }
        return header.toString();
    }

    static void addCookie(HttpServletResponse response, String token, long maxAgeSeconds,
                          boolean secure) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookieHeader(token, maxAgeSeconds, secure));
    }
}
