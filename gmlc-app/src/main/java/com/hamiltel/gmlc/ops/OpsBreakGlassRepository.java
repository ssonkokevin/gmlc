package com.hamiltel.gmlc.ops;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Emergency grants. A grant is bound to the session that raised it, so it dies
 * with that session, and it carries a review obligation from the moment it is
 * created. Reviewers are joined for display only; the grantee is never a
 * reviewer, which the table also enforces.
 */
@Repository
public class OpsBreakGlassRepository {

    private static final String COLUMNS = """
            g.grant_id, g.tenant_id, g.user_id, g.session_id, g.reason, g.granted_at,
            g.expires_at, g.ended_at, g.review_status, g.reviewed_by, g.reviewed_at,
            g.review_note, u.username, u.display_name, r.username AS reviewed_by_name
            """;

    private static final RowMapper<GrantRow> MAPPER = (rs, rowNum) -> new GrantRow(
            rs.getObject("grant_id", UUID.class),
            rs.getLong("tenant_id"),
            rs.getObject("user_id", UUID.class),
            rs.getObject("session_id", UUID.class),
            rs.getString("reason"),
            instant(rs, "granted_at"),
            instant(rs, "expires_at"),
            instant(rs, "ended_at"),
            rs.getString("review_status"),
            rs.getObject("reviewed_by", UUID.class),
            instant(rs, "reviewed_at"),
            rs.getString("review_note"),
            rs.getString("username"),
            rs.getString("display_name"),
            rs.getString("reviewed_by_name"));

    private static final String FROM = """
              FROM ops_break_glass g
              JOIN ops_user u ON u.user_id = g.user_id
              LEFT JOIN ops_user r ON r.user_id = g.reviewed_by
            """;

    private final JdbcTemplate jdbc;

    public OpsBreakGlassRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID grantId, long tenantId, UUID userId, UUID sessionId, String reason,
                       Instant expiresAt) {
        jdbc.update("""
                INSERT INTO ops_break_glass (grant_id, tenant_id, user_id, session_id, reason,
                    expires_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, grantId, tenantId, userId, sessionId, reason, Timestamp.from(expiresAt));
    }

    /** The unexpired grant on this session, if any; one active grant per user. */
    public Optional<GrantRow> activeForSession(long tenantId, UUID sessionId, Instant now) {
        List<GrantRow> rows = jdbc.query("SELECT " + COLUMNS + FROM
                        + " WHERE g.tenant_id = ? AND g.session_id = ? AND g.ended_at IS NULL"
                        + " AND g.expires_at > ?",
                MAPPER, tenantId, sessionId, Timestamp.from(now));
        return rows.stream().findFirst();
    }

    public Optional<GrantRow> find(long tenantId, UUID grantId) {
        List<GrantRow> rows = jdbc.query("SELECT " + COLUMNS + FROM
                        + " WHERE g.tenant_id = ? AND g.grant_id = ?",
                MAPPER, tenantId, grantId);
        return rows.stream().findFirst();
    }

    public Optional<GrantRow> activeForUser(long tenantId, UUID userId, Instant now) {
        List<GrantRow> rows = jdbc.query("SELECT " + COLUMNS + FROM
                        + " WHERE g.tenant_id = ? AND g.user_id = ? AND g.ended_at IS NULL"
                        + " AND g.expires_at > ?",
                MAPPER, tenantId, userId, Timestamp.from(now));
        return rows.stream().findFirst();
    }

    public List<GrantRow> list(long tenantId, String reviewStatus, int limit) {
        String status = reviewStatus == null ? "ALL" : reviewStatus;
        if ("ALL".equals(status)) {
            return jdbc.query("SELECT " + COLUMNS + FROM
                    + " WHERE g.tenant_id = ? ORDER BY g.granted_at DESC LIMIT ?",
                    MAPPER, tenantId, limit);
        }
        return jdbc.query("SELECT " + COLUMNS + FROM
                        + " WHERE g.tenant_id = ? AND g.review_status = ?"
                        + " ORDER BY g.granted_at DESC LIMIT ?",
                MAPPER, tenantId, status, limit);
    }

    /**
     * Closes grants whose window has passed. The end time is the promised
     * expiry, not the moment the sweep ran, so the record says when the grant
     * actually stopped granting.
     *
     * @return the number of grants closed
     */
    public int closeExpired(long tenantId, Instant now) {
        return jdbc.update("""
                UPDATE ops_break_glass SET ended_at = expires_at
                 WHERE tenant_id = ? AND ended_at IS NULL AND expires_at <= ?
                """, tenantId, Timestamp.from(now));
    }

    /** @return rows changed; 0 when the grant was already ended */
    public int end(long tenantId, UUID grantId, Instant endedAt) {
        return jdbc.update("""
                UPDATE ops_break_glass SET ended_at = ?
                 WHERE tenant_id = ? AND grant_id = ? AND ended_at IS NULL
                """, Timestamp.from(endedAt), tenantId, grantId);
    }

    /**
     * @return rows changed; 0 when the grant is already reviewed, still active
     *         or the reviewer is the grantee
     */
    public int review(long tenantId, UUID grantId, UUID reviewerId, String note, Instant now) {
        return jdbc.update("""
                UPDATE ops_break_glass
                   SET review_status = 'REVIEWED', reviewed_by = ?, reviewed_at = ?,
                       review_note = ?
                 WHERE tenant_id = ? AND grant_id = ? AND review_status = 'PENDING'
                   AND ended_at IS NOT NULL AND user_id <> ?
                """, reviewerId, Timestamp.from(now), note, tenantId, grantId, reviewerId);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    public record GrantRow(
            UUID grantId,
            long tenantId,
            UUID userId,
            UUID sessionId,
            String reason,
            Instant grantedAt,
            Instant expiresAt,
            Instant endedAt,
            String reviewStatus,
            UUID reviewedBy,
            Instant reviewedAt,
            String reviewNote,
            String username,
            String displayName,
            String reviewedByName) {
    }
}
