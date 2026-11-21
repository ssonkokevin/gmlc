package com.hamiltel.gmlc.ops;

import com.hamiltel.gmlc.audit.AuditTrail;
import com.hamiltel.gmlc.config.GmlcProperties;
import com.hamiltel.gmlc.config.OpsProperties;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Operator sessions. A login needs a password and a TOTP code; a success
 * issues a random bearer token that is stored only as a SHA-256. The token
 * lives in an HttpOnly cookie, the CSRF token is returned in the body so the
 * console can echo it in a header - never in a readable cookie.
 */
@Service
public class OpsSessionService {

    private static final String TIMING_ANCHOR = Argon2PasswordHasher.timingAnchor();

    private final OpsUserRepository users;
    private final OpsSessionRepository sessions;
    private final Argon2PasswordHasher hasher;
    private final OpsProperties properties;
    private final GmlcProperties gmlc;
    private final AuditTrail audit;
    private final SecureRandom random = new SecureRandom();
    private final Base64.Encoder urlSafe = Base64.getUrlEncoder().withoutPadding();

    public OpsSessionService(OpsUserRepository users, OpsSessionRepository sessions,
                             Argon2PasswordHasher hasher, OpsProperties properties,
                             GmlcProperties gmlc, AuditTrail audit) {
        this.users = users;
        this.sessions = sessions;
        this.hasher = hasher;
        this.properties = properties;
        this.gmlc = gmlc;
        this.audit = audit;
    }

    public Issued login(String username, char[] password, String totpCode, String remoteAddr) {
        long tenantId = gmlc.defaultTenantId();
        Instant now = Instant.now();
        Optional<OpsUser> found = users.findByUsername(tenantId, username);

        if (found.isEmpty()) {
            // Same cost as a real check, so a wrong username is not faster.
            hasher.burn(password, TIMING_ANCHOR);
            audit.recordOperator(tenantId, null, "OPS_LOGIN", AuditTrail.Outcome.DENIED,
                    detail("UNKNOWN_USER", remoteAddr));
            throw new OpsAuthenticationException();
        }

        OpsUser user = found.get();
        if (user.isLocked(now)) {
            audit.recordOperator(tenantId, user.userId(), "OPS_LOGIN", AuditTrail.Outcome.DENIED,
                    detail("LOCKED", remoteAddr));
            throw new OpsLockedException(user.lockedUntil());
        }

        boolean passwordOk = hasher.verify(password, user.passwordHash());
        boolean totpOk = Totp.verify(user.totpSecret(), totpCode, now);
        if (!passwordOk || !totpOk) {
            users.recordFailure(tenantId, user.userId(),
                    properties.lockoutThreshold(), properties.lockoutMinutes());
            audit.recordOperator(tenantId, user.userId(), "OPS_LOGIN", AuditTrail.Outcome.DENIED,
                    detail(passwordOk ? "TOTP_INVALID" : "PASSWORD_INVALID", remoteAddr));
            throw new OpsAuthenticationException();
        }

        if (!user.enabled()) {
            audit.recordOperator(tenantId, user.userId(), "OPS_LOGIN", AuditTrail.Outcome.DENIED,
                    detail("DISABLED", remoteAddr));
            throw new OpsAuthenticationException();
        }

        users.recordSuccess(tenantId, user.userId());
        sessions.deleteExpiredBefore(now.minus(Duration.ofDays(1)));

        String token = token();
        String csrf = token();
        Instant absoluteExpires = now.plus(Duration.ofHours(properties.sessionAbsoluteHours()));
        Instant idleExpires = earlier(now.plus(Duration.ofMinutes(properties.sessionIdleMinutes())),
                absoluteExpires);
        UUID sessionId = UUID.randomUUID();
        sessions.insert(sessionId, sha256(token), user.userId(), tenantId, csrf,
                truncate(remoteAddr), idleExpires, absoluteExpires);

        audit.recordOperator(tenantId, user.userId(), "OPS_LOGIN", AuditTrail.Outcome.ALLOWED,
                detail("PASSWORD_AND_TOTP", remoteAddr));

        OpsUser refreshed = users.find(tenantId, user.userId()).orElse(user);
        return new Issued(token, csrf, refreshed, sessionId, idleExpires, absoluteExpires);
    }

    /**
     * @return the operator and session for a live token; empty for a missing,
     *         revoked, expired or disabled one. An idle or absolute expiry
     *         revokes the row so a stolen token cannot be retried.
     */
    public Optional<OpsContext> authenticate(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        Optional<OpsSession> found = sessions.findByTokenHash(sha256(token));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        OpsSession session = found.get();
        Instant now = Instant.now();
        if (session.revokedAt() != null
                || now.isAfter(session.absoluteExpiresAt())
                || now.isAfter(session.idleExpiresAt())) {
            sessions.revoke(session.sessionId());
            return Optional.empty();
        }
        Optional<OpsUser> user = users.find(session.tenantId(), session.userId());
        if (user.isEmpty() || !user.get().enabled()) {
            sessions.revoke(session.sessionId());
            return Optional.empty();
        }
        Instant idleExpires = earlier(now.plus(Duration.ofMinutes(properties.sessionIdleMinutes())),
                session.absoluteExpiresAt());
        sessions.touch(session.sessionId(), idleExpires);
        return Optional.of(new OpsContext(user.get(), session.sessionId(), session.csrfToken()));
    }

    public void logout(OpsContext context, String remoteAddr) {
        sessions.revoke(context.sessionId());
        audit.recordOperator(context.tenantId(), context.user().userId(), "OPS_LOGOUT",
                AuditTrail.Outcome.ALLOWED, detail("OPERATOR_REQUEST", remoteAddr));
    }

    /** Ends every session a user holds, and names the operator who asked. */
    public int revokeAll(long tenantId, UUID actorUserId, UUID targetUserId, UUID exceptSessionId,
                         String reason) {
        int ended = sessions.revokeAllForUser(tenantId, targetUserId, exceptSessionId);
        audit.recordOperator(tenantId, actorUserId, "OPS_SESSION_REVOKED", AuditTrail.Outcome.ALLOWED,
                Map.of("targetUser", targetUserId.toString(), "reason", reason,
                        "sessionsEnded", ended));
        return ended;
    }

    public static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private String token() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return urlSafe.encodeToString(bytes);
    }

    private static Instant earlier(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 64 ? value : value.substring(0, 64);
    }

    private static Map<String, Object> detail(String reason, String remoteAddr) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("reason", reason);
        if (remoteAddr != null && !remoteAddr.isBlank()) {
            detail.put("remoteAddr", truncate(remoteAddr));
        }
        return detail;
    }

    public record Issued(String token, String csrfToken, OpsUser user, UUID sessionId,
                         Instant idleExpiresAt, Instant absoluteExpiresAt) {
    }
}
