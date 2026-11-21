package com.hamiltel.gmlc.ops;

import java.time.Instant;
import java.util.UUID;

/** An operator account, including the material only the session service needs. */
public record OpsUser(
        UUID userId,
        long tenantId,
        String username,
        String displayName,
        OpsRole role,
        String passwordHash,
        String totpSecret,
        boolean enabled,
        int failedAttempts,
        Instant lockedUntil,
        Instant lastLoginAt,
        Instant createdAt) {

    public boolean isLocked(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }
}
