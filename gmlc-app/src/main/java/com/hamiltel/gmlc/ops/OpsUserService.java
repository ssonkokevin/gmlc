package com.hamiltel.gmlc.ops;

import com.hamiltel.gmlc.audit.AuditTrail;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Creating and changing operator accounts. The one-time password and the TOTP
 * secret are returned exactly once, by the create call; no read path exposes
 * them and the audit row records only that an account was created.
 */
@Service
public class OpsUserService {

    private final OpsUserRepository users;
    private final OpsSessionService sessions;
    private final Argon2PasswordHasher hasher;
    private final AuditTrail audit;
    private final SecureRandom random = new SecureRandom();
    private final Base64.Encoder urlSafe = Base64.getUrlEncoder().withoutPadding();

    public OpsUserService(OpsUserRepository users, OpsSessionService sessions,
                          Argon2PasswordHasher hasher, AuditTrail audit) {
        this.users = users;
        this.sessions = sessions;
        this.hasher = hasher;
        this.audit = audit;
    }

    public List<OpsUser> list(long tenantId) {
        return users.list(tenantId);
    }

    public OpsUser get(long tenantId, UUID userId) {
        return users.find(tenantId, userId)
                .orElseThrow(() -> new OpsNotFoundException("operator"));
    }

    public Created create(long tenantId, OpsContext actor, String username, String displayName,
                          OpsRole role, boolean enabled) {
        String normalised = username.trim().toLowerCase(java.util.Locale.ROOT);
        byte[] passwordBytes = new byte[24];
        random.nextBytes(passwordBytes);
        String oneTimePassword = urlSafe.encodeToString(passwordBytes);
        String totpSecret = Totp.generateSecret();
        UUID userId = UUID.randomUUID();
        try {
            users.insert(userId, tenantId, normalised, displayName.trim(), role,
                    hasher.hash(oneTimePassword.toCharArray()), totpSecret, enabled);
        } catch (DuplicateKeyException e) {
            throw new OpsConflictException("USERNAME_EXISTS", "that username is already taken");
        }
        audit.recordOperator(tenantId, actor.user().userId(), "OPS_USER_CREATED",
                AuditTrail.Outcome.ALLOWED, Map.of("targetUser", userId.toString(), "role", role.name()));
        return new Created(get(tenantId, userId), oneTimePassword, totpSecret);
    }

    public OpsUser update(long tenantId, OpsContext actor, UUID userId, Optional<OpsRole> role,
                          Optional<Boolean> enabled) {
        OpsUser target = get(tenantId, userId);
        OpsRole nextRole = role.orElse(target.role());
        boolean nextEnabled = enabled.orElse(target.enabled());

        if (target.userId().equals(actor.user().userId()) && !nextEnabled) {
            throw new OpsConflictException("SELF_DISABLE",
                    "an operator cannot disable their own account");
        }
        boolean losesAdmin = target.role() == OpsRole.ADMIN
                && (nextRole != OpsRole.ADMIN || !nextEnabled);
        if (losesAdmin && users.countEnabledAdmins(tenantId) <= 1) {
            throw new OpsConflictException("LAST_ADMIN",
                    "the console must keep at least one enabled administrator");
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("targetUser", userId.toString());
        if (nextRole != target.role()) {
            users.updateRole(tenantId, userId, nextRole);
            detail.put("role", nextRole.name());
        }
        if (nextEnabled != target.enabled()) {
            users.setEnabled(tenantId, userId, nextEnabled);
            detail.put("enabled", nextEnabled);
            if (!nextEnabled) {
                sessions.revokeAll(tenantId, actor.user().userId(), userId, null, "ACCOUNT_DISABLED");
            }
        }
        if (!detail.containsKey("role") && !detail.containsKey("enabled")) {
            return target;
        }
        audit.recordOperator(tenantId, actor.user().userId(), "OPS_USER_UPDATED",
                AuditTrail.Outcome.ALLOWED, detail);
        return get(tenantId, userId);
    }

    public void changePassword(long tenantId, OpsContext actor, UUID userId, char[] newPassword) {
        OpsUser target = get(tenantId, userId);
        users.changePassword(tenantId, userId, hasher.hash(newPassword));
        UUID keep = target.userId().equals(actor.user().userId()) ? actor.sessionId() : null;
        sessions.revokeAll(tenantId, actor.user().userId(), userId, keep, "PASSWORD_CHANGED");
        audit.recordOperator(tenantId, actor.user().userId(), "OPS_PASSWORD_CHANGED",
                AuditTrail.Outcome.ALLOWED, Map.of("targetUser", target.userId().toString()));
    }

    public record Created(OpsUser user, String oneTimePassword, String totpSecret) {
    }
}
