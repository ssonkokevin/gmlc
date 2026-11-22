package com.hamiltel.gmlc.ops;

import com.hamiltel.gmlc.audit.AuditTrail;
import com.hamiltel.gmlc.config.OpsProperties;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Emergency elevation. An operator who holds the sealed deployment code may
 * add the ADMIN permission set to their current session for half an hour, and
 * must write why. The grant is bound to that session, announced immediately in
 * the audit trail, and carries a review obligation from creation: once the
 * window closes a different administrator must record what the elevation was
 * used for. Nobody can review their own grant.
 *
 * <p>The code is compared by SHA-256 against a hash from the environment. The
 * code itself is never configured, logged or stored; a deployment with no hash
 * has break-glass switched off.
 */
@Service
public class OpsBreakGlassService {

    static final Duration WINDOW = Duration.ofMinutes(30);
    static final int REASON_MIN = 20;
    static final int REVIEW_NOTE_MIN = 10;

    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

    private final OpsBreakGlassRepository grants;
    private final OpsProperties properties;
    private final AuditTrail audit;

    public OpsBreakGlassService(OpsBreakGlassRepository grants, OpsProperties properties,
                                AuditTrail audit) {
        this.grants = grants;
        this.properties = properties;
        this.audit = audit;
    }

    public boolean configured() {
        return configuredHash(properties.breakGlassCodeHash());
    }

    /**
     * Whether a configured hash can ever match. A deployment that clears the
     * hash switches the feature off, and an unexpired grant left in the table
     * stops elevating at the same moment.
     */
    static boolean configuredHash(String hash) {
        return hash != null
                && SHA256_HEX.matcher(hash.trim().toLowerCase(Locale.ROOT)).matches();
    }

    public GrantView start(OpsContext context, String reason, String code, String remoteAddr) {
        long tenantId = context.tenantId();
        UUID actorId = context.user().userId();
        String cleanReason = clean(reason);
        if (cleanReason == null || cleanReason.length() < REASON_MIN) {
            throw new OpsBadRequestException("BREAK_GLASS_REASON_REQUIRED",
                    "break-glass needs a reason of at least " + REASON_MIN + " characters");
        }
        if (!configured()) {
            throw new OpsConflictException("BREAK_GLASS_DISABLED",
                    "break-glass is disabled because this deployment has no emergency code");
        }
        if (!codeMatches(code)) {
            audit.recordOperator(tenantId, actorId, "BREAK_GLASS_STARTED",
                    AuditTrail.Outcome.DENIED, detail("CODE_INVALID", remoteAddr));
            throw new OpsBreakGlassRejectedException();
        }

        Instant now = Instant.now();
        grants.closeExpired(tenantId, now);
        if (grants.activeForUser(tenantId, actorId, now).isPresent()) {
            throw new OpsConflictException("BREAK_GLASS_ACTIVE",
                    "this account already holds an active emergency grant");
        }

        UUID grantId = UUID.randomUUID();
        Instant expiresAt = now.plus(WINDOW);
        try {
            grants.insert(grantId, tenantId, actorId, context.sessionId(), cleanReason, expiresAt);
        } catch (DuplicateKeyException e) {
            // Two requests raced; the partial unique index kept the first.
            throw new OpsConflictException("BREAK_GLASS_ACTIVE",
                    "this account already holds an active emergency grant");
        }

        Map<String, Object> detail = detail("GRANTED", remoteAddr);
        detail.put("grantId", grantId.toString());
        detail.put("reason", cleanReason);
        detail.put("expiresAt", expiresAt.toString());
        detail.put("reviewRequired", true);
        audit.recordOperator(tenantId, actorId, "BREAK_GLASS_STARTED",
                AuditTrail.Outcome.ALLOWED, detail);

        return view(grants.find(tenantId, grantId).orElseThrow(), now);
    }

    public GrantView end(OpsContext context) {
        long tenantId = context.tenantId();
        Instant now = Instant.now();
        OpsBreakGlassRepository.GrantRow grant = grants
                .activeForSession(tenantId, context.sessionId(), now)
                .orElseThrow(() -> new OpsConflictException("BREAK_GLASS_NOT_ACTIVE",
                        "this session holds no active emergency grant"));
        grants.end(tenantId, grant.grantId(), now);
        audit.recordOperator(tenantId, context.user().userId(), "BREAK_GLASS_ENDED",
                AuditTrail.Outcome.ALLOWED,
                Map.of("grantId", grant.grantId().toString()));
        return view(grants.find(tenantId, grant.grantId()).orElse(grant), now);
    }

    public List<GrantView> list(long tenantId, String reviewStatus, int limit) {
        String status = reviewStatus == null || reviewStatus.isBlank()
                ? "ALL" : reviewStatus.trim().toUpperCase(Locale.ROOT);
        if (!List.of("ALL", "PENDING", "REVIEWED").contains(status)) {
            throw new OpsBadRequestException("BREAK_GLASS_STATUS_NOT_SUPPORTED",
                    "status must be ALL, PENDING or REVIEWED");
        }
        Instant now = Instant.now();
        grants.closeExpired(tenantId, now);
        return grants.list(tenantId, status, limit).stream()
                .map(row -> view(row, now))
                .toList();
    }

    public GrantView review(OpsContext context, UUID grantId, String note) {
        long tenantId = context.tenantId();
        UUID reviewerId = context.user().userId();
        String cleanNote = clean(note);
        if (cleanNote == null || cleanNote.length() < REVIEW_NOTE_MIN) {
            throw new OpsBadRequestException("BREAK_GLASS_REVIEW_NOTE_REQUIRED",
                    "a review needs a note of at least " + REVIEW_NOTE_MIN + " characters");
        }

        Instant now = Instant.now();
        grants.closeExpired(tenantId, now);
        OpsBreakGlassRepository.GrantRow grant = grants.find(tenantId, grantId)
                .orElseThrow(() -> new OpsNotFoundException("break-glass grant"));
        if ("REVIEWED".equals(grant.reviewStatus())) {
            throw new OpsConflictException("BREAK_GLASS_ALREADY_REVIEWED",
                    "this grant has already been reviewed");
        }
        if (grant.endedAt() == null) {
            throw new OpsConflictException("BREAK_GLASS_STILL_ACTIVE",
                    "this grant is still active; review it once it has ended");
        }
        if (grant.userId().equals(reviewerId)) {
            audit.recordOperator(tenantId, reviewerId, "BREAK_GLASS_SELF_REVIEW",
                    AuditTrail.Outcome.DENIED, Map.of("grantId", grantId.toString()));
            throw new OpsConflictException("BREAK_GLASS_SELF_REVIEW",
                    "the operator who raised a grant cannot review it");
        }
        if (grants.review(tenantId, grantId, reviewerId, cleanNote, now) != 1) {
            throw new OpsConflictException("BREAK_GLASS_ALREADY_REVIEWED",
                    "this grant has already been reviewed");
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("grantId", grantId.toString());
        detail.put("targetUser", grant.userId().toString());
        detail.put("note", cleanNote);
        audit.recordOperator(tenantId, reviewerId, "BREAK_GLASS_REVIEWED",
                AuditTrail.Outcome.ALLOWED, detail);
        return view(grants.find(tenantId, grantId).orElseThrow(), now);
    }

    static GrantView view(OpsBreakGlassRepository.GrantRow row, Instant now) {
        boolean active = row.endedAt() == null && row.expiresAt().isAfter(now);
        Instant endedAt = row.endedAt() != null ? row.endedAt()
                : active ? null : row.expiresAt();
        boolean reviewDue = !active && "PENDING".equals(row.reviewStatus());
        return new GrantView(row.grantId(), row.userId(), row.username(), row.displayName(),
                row.sessionId(), row.reason(), row.grantedAt(), row.expiresAt(), endedAt, active,
                row.reviewStatus(), row.reviewedBy(), row.reviewedByName(), row.reviewedAt(),
                row.reviewNote(), reviewDue);
    }

    private boolean codeMatches(String code) {
        if (code == null || code.isBlank()) {
            return false;
        }
        byte[] expected = normalisedHash().getBytes(StandardCharsets.US_ASCII);
        byte[] actual = OpsSessionService.sha256(code)
                .getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(actual, expected);
    }

    private String normalisedHash() {
        String hash = properties.breakGlassCodeHash();
        return hash == null ? "" : hash.trim().toLowerCase(Locale.ROOT);
    }

    private static String clean(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Map<String, Object> detail(String reason, String remoteAddr) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("reason", reason);
        if (remoteAddr != null && !remoteAddr.isBlank()) {
            detail.put("remoteAddr", remoteAddr.length() <= 64
                    ? remoteAddr : remoteAddr.substring(0, 64));
        }
        return detail;
    }

    public record GrantView(
            UUID grantId,
            UUID userId,
            String username,
            String displayName,
            UUID sessionId,
            String reason,
            Instant grantedAt,
            Instant expiresAt,
            Instant endedAt,
            boolean active,
            String reviewStatus,
            UUID reviewedBy,
            String reviewedByName,
            Instant reviewedAt,
            String reviewNote,
            boolean reviewDue) {
    }
}
