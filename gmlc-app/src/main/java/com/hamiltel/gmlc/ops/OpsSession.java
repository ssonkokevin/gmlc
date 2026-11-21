package com.hamiltel.gmlc.ops;

import java.time.Instant;
import java.util.UUID;

/** A stored session. The raw token is never stored, only its SHA-256. */
public record OpsSession(
        UUID sessionId,
        String tokenHash,
        UUID userId,
        long tenantId,
        String csrfToken,
        String remoteAddr,
        Instant createdAt,
        Instant lastSeenAt,
        Instant idleExpiresAt,
        Instant absoluteExpiresAt,
        Instant revokedAt) {
}
