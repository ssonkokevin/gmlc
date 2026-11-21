package com.hamiltel.gmlc.consent;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ConsentRepository {

    private final JdbcTemplate jdbc;

    public ConsentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One live consent per (subject, purpose, client): re-granting refreshes
     * the existing row instead of stacking duplicates, so revoking it later is
     * unambiguous.
     */
    public UUID upsert(long tenantId, UUID consentId, String subjectRef, UUID clientId,
                       String purpose, String channel, Instant expiresAt, String evidenceJson) {
        List<UUID> existing = jdbc.query(
                "SELECT consent_id FROM consent"
                        + " WHERE tenant_id = ? AND subject_ref = ? AND purpose = ?"
                        + " AND client_id = ? AND status IN ('PENDING', 'GRANTED')",
                (rs, rowNum) -> rs.getObject("consent_id", UUID.class),
                tenantId, subjectRef, purpose, clientId);
        if (!existing.isEmpty()) {
            UUID kept = existing.get(0);
            jdbc.update("""
                    UPDATE consent
                    SET status = 'GRANTED', granted_at = now(), expires_at = ?,
                        revoked_at = NULL, channel = ?, evidence = ?::jsonb
                    WHERE tenant_id = ? AND consent_id = ?
                    """,
                    expiresAt == null ? null : Timestamp.from(expiresAt),
                    channel, evidenceJson, tenantId, kept);
            return kept;
        }
        jdbc.update("""
                INSERT INTO consent (consent_id, tenant_id, subject_ref, client_id, purpose,
                    status, channel, granted_at, expires_at, evidence)
                VALUES (?, ?, ?, ?, ?, 'GRANTED', ?, now(), ?, ?::jsonb)
                """,
                consentId, tenantId, subjectRef, clientId, purpose, channel,
                expiresAt == null ? null : Timestamp.from(expiresAt), evidenceJson);
        return consentId;
    }

    /**
     * Client-specific consent wins over a tenant-wide row; among equals the
     * most recently granted wins. Expired rows are not returned.
     */
    public Optional<Consent> findActive(long tenantId, String subjectRef, String purpose,
                                        UUID clientId) {
        List<Consent> rows = jdbc.query("""
                SELECT consent_id, subject_ref, purpose, status, client_id, granted_at, expires_at
                FROM consent
                WHERE tenant_id = ? AND subject_ref = ? AND purpose = ?
                  AND status = 'GRANTED'
                  AND (expires_at IS NULL OR expires_at > now())
                  AND (client_id IS NULL OR client_id = ?)
                ORDER BY (client_id IS NULL), granted_at DESC
                LIMIT 1
                """, MAPPER, tenantId, subjectRef, purpose, clientId);
        return rows.stream().findFirst();
    }

    public Optional<Consent> find(long tenantId, UUID clientId, UUID consentId) {
        List<Consent> rows = jdbc.query("""
                SELECT consent_id, subject_ref, purpose, status, client_id, granted_at, expires_at
                FROM consent
                WHERE tenant_id = ? AND consent_id = ? AND client_id = ?
                """, MAPPER, tenantId, consentId, clientId);
        return rows.stream().findFirst();
    }

    public boolean revoke(long tenantId, UUID clientId, UUID consentId) {
        int rows = jdbc.update("""
                UPDATE consent
                SET status = 'REVOKED', revoked_at = now()
                WHERE tenant_id = ? AND consent_id = ? AND client_id = ?
                  AND status IN ('PENDING', 'GRANTED')
                """, tenantId, consentId, clientId);
        return rows == 1;
    }

    private static final RowMapper<Consent> MAPPER = (rs, rowNum) -> new Consent(
            rs.getObject("consent_id", UUID.class),
            rs.getString("subject_ref"),
            rs.getString("purpose"),
            rs.getString("status"),
            rs.getObject("client_id", UUID.class),
            rs.getTimestamp("granted_at") == null ? null : rs.getTimestamp("granted_at").toInstant(),
            rs.getTimestamp("expires_at") == null ? null : rs.getTimestamp("expires_at").toInstant());

    public record Consent(UUID consentId, String subjectRef, String purpose, String status,
                          UUID clientId, Instant grantedAt, Instant expiresAt) {
    }
}
