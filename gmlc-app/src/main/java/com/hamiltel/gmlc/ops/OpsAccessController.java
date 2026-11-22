package com.hamiltel.gmlc.ops;

import com.hamiltel.gmlc.client.ApiClientRepository;
import com.hamiltel.gmlc.config.OpsProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Users and access: the role matrix, live sessions across every operator, the
 * audit log, the sign-in policy, the client register and break-glass. Reading
 * any of it needs MANAGE_USERS, except the client register, which needs
 * MANAGE_CLIENTS. Starting or ending your own emergency elevation needs only a
 * signed-in session - the sealed code is the second factor - while seeing or
 * reviewing grants needs MANAGE_USERS.
 *
 * <p>The audit view returns what the trail stores. It names people and
 * clients, the route or action, the remote address and the outcome; by design
 * it holds no coordinate and no raw subscriber identifier.
 */
@RestController
@RequestMapping("/v1/ops/access")
public class OpsAccessController {

    private static final int MAX_AUDIT_LIMIT = 500;
    private static final int DEFAULT_AUDIT_LIMIT = 100;
    private static final int MAX_GRANT_LIMIT = 200;
    private static final int DEFAULT_GRANT_LIMIT = 50;

    private final OpsBreakGlassService breakGlass;
    private final OpsSessionService sessions;
    private final OpsSessionRepository sessionRows;
    private final OpsAuditRepository auditEvents;
    private final ApiClientRepository clients;
    private final OpsProperties properties;

    public OpsAccessController(OpsBreakGlassService breakGlass, OpsSessionService sessions,
                               OpsSessionRepository sessionRows, OpsAuditRepository auditEvents,
                               ApiClientRepository clients, OpsProperties properties) {
        this.breakGlass = breakGlass;
        this.sessions = sessions;
        this.sessionRows = sessionRows;
        this.auditEvents = auditEvents;
        this.clients = clients;
        this.properties = properties;
    }

    @GetMapping("/roles")
    @OpsRequires(OpsPermission.MANAGE_USERS)
    public RoleMatrix roles() {
        List<String> permissions = Arrays.stream(OpsPermission.values())
                .map(Enum::name).sorted().toList();
        List<RoleView> matrix = Arrays.stream(OpsRole.values())
                .map(role -> new RoleView(role.name(), role.permissions().stream()
                        .map(Enum::name).sorted().toList()))
                .toList();
        return new RoleMatrix(permissions, matrix);
    }

    @GetMapping("/sessions")
    @OpsRequires(OpsPermission.MANAGE_USERS)
    public List<SessionRow> sessions(@OpsCurrentSession OpsContext context) {
        return sessionRows.listActive(context.tenantId(), Instant.now()).stream()
                .map(row -> new SessionRow(row.sessionId(), row.userId(), row.username(),
                        row.displayName(), row.role(), row.remoteAddr(), row.createdAt(),
                        row.lastSeenAt(), row.expiresAt(),
                        row.sessionId().equals(context.sessionId())))
                .toList();
    }

    @PostMapping("/sessions/{sessionId}/sign-out")
    @OpsRequires(OpsPermission.MANAGE_USERS)
    public ResponseEntity<Void> signOut(@OpsCurrentSession OpsContext context,
                                        @PathVariable UUID sessionId) {
        sessions.revokeOne(context.tenantId(), context.user().userId(), sessionId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/audit")
    @OpsRequires(OpsPermission.MANAGE_USERS)
    public List<OpsAuditRepository.AuditRow> audit(
            @OpsCurrentSession OpsContext context,
            @RequestParam(required = false) String action,
            @RequestParam(defaultValue = "ALL") String outcome,
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) Long before,
            @RequestParam(defaultValue = "" + DEFAULT_AUDIT_LIMIT) int limit) {
        if (limit < 1 || limit > MAX_AUDIT_LIMIT) {
            throw new OpsBadRequestException("LIMIT_OUT_OF_RANGE",
                    "limit must be between 1 and " + MAX_AUDIT_LIMIT);
        }
        String filterOutcome = blankToNull(outcome);
        if (filterOutcome != null && "ALL".equalsIgnoreCase(filterOutcome)) {
            filterOutcome = null;
        } else if (filterOutcome != null) {
            filterOutcome = filterOutcome.toUpperCase(Locale.ROOT);
            if (!List.of("ALLOWED", "DENIED", "ERROR").contains(filterOutcome)) {
                throw new OpsBadRequestException("AUDIT_OUTCOME_NOT_SUPPORTED",
                        "outcome must be ALLOWED, DENIED, ERROR or ALL");
            }
        }
        String filterAction = blankToNull(action);
        if (filterAction != null) {
            if (filterAction.length() > 48) {
                throw new OpsBadRequestException("AUDIT_ACTION_TOO_LONG",
                        "action must be at most 48 characters");
            }
            filterAction = filterAction.toUpperCase(Locale.ROOT);
        }
        String filterActor = blankToNull(actor);
        if (filterActor != null && filterActor.length() > 128) {
            throw new OpsBadRequestException("AUDIT_ACTOR_TOO_LONG",
                    "actor must be at most 128 characters");
        }
        Instant fromInstant = instant(from, "from");
        Instant toInstant = instant(to, "to");
        if (fromInstant != null && toInstant != null && !fromInstant.isBefore(toInstant)) {
            throw new OpsBadRequestException("AUDIT_RANGE_INVALID",
                    "from must be earlier than to");
        }
        return auditEvents.list(context.tenantId(), filterAction, filterOutcome, filterActor,
                fromInstant, toInstant, before, limit);
    }

    @GetMapping("/policy")
    @OpsRequires(OpsPermission.MANAGE_USERS)
    public PolicyView policy() {
        return new PolicyView(
                true,
                OpsUserController.PASSWORD_MIN_LENGTH,
                Totp.DIGITS,
                Totp.STEP_SECONDS,
                properties.totpIssuer(),
                properties.lockoutThreshold(),
                properties.lockoutMinutes(),
                properties.sessionIdleMinutes(),
                properties.sessionAbsoluteHours(),
                "ARGON2ID",
                properties.argon2MemoryKib(),
                properties.argon2Iterations(),
                properties.argon2Parallelism(),
                OpsBreakGlassService.WINDOW.toMinutes(),
                breakGlass.configured(),
                "ADMIN");
    }

    @GetMapping("/clients")
    @OpsRequires(OpsPermission.MANAGE_CLIENTS)
    public List<ApiClientRepository.ClientAdminRow> clients(@OpsCurrentSession OpsContext context) {
        return clients.listAll(context.tenantId());
    }

    @PostMapping("/break-glass")
    public ResponseEntity<OpsBreakGlassService.GrantView> startBreakGlass(
            @OpsCurrentSession OpsContext context,
            @Valid @RequestBody BreakGlassRequest body,
            HttpServletRequest request) {
        OpsBreakGlassService.GrantView grant = breakGlass.start(context, body.reason(),
                body.code(), request.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.CREATED).body(grant);
    }

    @DeleteMapping("/break-glass")
    public OpsBreakGlassService.GrantView endBreakGlass(@OpsCurrentSession OpsContext context) {
        return breakGlass.end(context);
    }

    @GetMapping("/break-glass")
    @OpsRequires(OpsPermission.MANAGE_USERS)
    public List<OpsBreakGlassService.GrantView> breakGlassGrants(
            @OpsCurrentSession OpsContext context,
            @RequestParam(defaultValue = "ALL") String status,
            @RequestParam(defaultValue = "" + DEFAULT_GRANT_LIMIT) int limit) {
        if (limit < 1 || limit > MAX_GRANT_LIMIT) {
            throw new OpsBadRequestException("LIMIT_OUT_OF_RANGE",
                    "limit must be between 1 and " + MAX_GRANT_LIMIT);
        }
        return breakGlass.list(context.tenantId(), status, limit);
    }

    @PostMapping("/break-glass/{grantId}/review")
    @OpsRequires(OpsPermission.MANAGE_USERS)
    public OpsBreakGlassService.GrantView reviewBreakGlass(
            @OpsCurrentSession OpsContext context,
            @PathVariable UUID grantId,
            @Valid @RequestBody ReviewRequest body) {
        return breakGlass.review(context, grantId, body.note());
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Instant instant(String value, String field) {
        String trimmed = blankToNull(value);
        if (trimmed == null) {
            return null;
        }
        try {
            return Instant.parse(trimmed);
        } catch (DateTimeParseException e) {
            throw new OpsBadRequestException("AUDIT_TIME_INVALID",
                    field + " must be an ISO-8601 instant such as 2026-01-31T09:00:00Z");
        }
    }

    public record RoleMatrix(List<String> permissions, List<RoleView> roles) {
    }

    public record RoleView(String role, List<String> permissions) {
    }

    public record SessionRow(
            UUID sessionId,
            UUID userId,
            String username,
            String displayName,
            String role,
            String remoteAddr,
            Instant createdAt,
            Instant lastSeenAt,
            Instant expiresAt,
            boolean current) {
    }

    public record PolicyView(
            boolean mfaRequired,
            int passwordMinLength,
            int totpDigits,
            int totpPeriodSeconds,
            String totpIssuer,
            long lockoutThreshold,
            long lockoutMinutes,
            long sessionIdleMinutes,
            long sessionAbsoluteHours,
            String passwordHash,
            int argon2MemoryKib,
            int argon2Iterations,
            int argon2Parallelism,
            long breakGlassMinutes,
            boolean breakGlassConfigured,
            String breakGlassScope) {
    }

    public record BreakGlassRequest(
            @NotBlank @Size(max = 500) String reason,
            @NotBlank @Size(max = 200) String code) {
    }

    public record ReviewRequest(@Size(max = 500) String note) {
    }
}
