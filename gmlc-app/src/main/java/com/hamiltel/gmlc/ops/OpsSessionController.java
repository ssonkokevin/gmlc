package com.hamiltel.gmlc.ops;

import com.hamiltel.gmlc.config.OpsProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Sign-in, sign-out and who-am-I. The cookie is the only credential the
 * browser keeps; the CSRF token is handed back in the JSON body so the console
 * can echo it in a header. Neither is stored in a place a script can read by
 * accident: the cookie is HttpOnly, the CSRF token lives in memory.
 */
@RestController
@RequestMapping("/v1/ops")
public class OpsSessionController {

    private final OpsSessionService sessions;
    private final OpsSessionRepository sessionRepository;
    private final OpsProperties properties;

    public OpsSessionController(OpsSessionService sessions, OpsSessionRepository sessionRepository,
                                OpsProperties properties) {
        this.sessions = sessions;
        this.sessionRepository = sessionRepository;
        this.properties = properties;
    }

    @PostMapping("/session")
    public ResponseEntity<SessionView> login(@Valid @RequestBody LoginRequest body,
                                             HttpServletRequest request,
                                             HttpServletResponse response) {
        OpsSessionService.Issued issued = sessions.login(body.username(), body.password().toCharArray(),
                body.totp(), request.getRemoteAddr());
        long maxAge = Math.max(1, Duration.between(Instant.now(), issued.absoluteExpiresAt()).toSeconds());
        OpsSessionFilter.addCookie(response, issued.token(), maxAge, properties.cookieSecure());
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(SessionView.of(issued.user(), issued.csrfToken(), issued.absoluteExpiresAt(),
                        properties));
    }

    @DeleteMapping("/session")
    public ResponseEntity<Void> logout(@OpsCurrentSession OpsContext context,
                                       HttpServletRequest request,
                                       HttpServletResponse response) {
        sessions.logout(context, request.getRemoteAddr());
        OpsSessionFilter.addCookie(response, null, 0, properties.cookieSecure());
        return ResponseEntity.noContent().header("Cache-Control", "no-store").build();
    }

    @GetMapping("/me")
    public SessionView me(@OpsCurrentSession OpsContext context) {
        Instant expiresAt = sessionRepository.find(context.sessionId())
                .map(OpsSession::absoluteExpiresAt)
                .orElse(Instant.now());
        return SessionView.of(context.user(), context.csrfToken(), expiresAt, properties);
    }

    public record LoginRequest(
            @NotBlank String username,
            @NotBlank String password,
            @NotBlank @Pattern(regexp = "[0-9]{6}", message = "must be six digits") String totp) {
    }

    public record SessionView(
            UUID userId,
            String username,
            String displayName,
            String role,
            List<String> permissions,
            String csrfToken,
            Instant expiresAt,
            String environment,
            String dataSource) {

        static SessionView of(OpsUser user, String csrfToken, Instant expiresAt,
                              OpsProperties properties) {
            return new SessionView(user.userId(), user.username(), user.displayName(),
                    user.role().name(),
                    user.role().permissions().stream().map(Enum::name).sorted().toList(),
                    csrfToken, expiresAt,
                    properties.normalisedEnvironment(), properties.dataSourceLabel());
        }
    }
}
