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

@Repository
public class OpsSessionRepository {

    private static final String COLUMNS = """
            session_id, token_hash, user_id, tenant_id, csrf_token, remote_addr,
            created_at, last_seen_at, idle_expires_at, absolute_expires_at, revoked_at
            """;

    private static final RowMapper<OpsSession> MAPPER = (rs, rowNum) -> new OpsSession(
            rs.getObject("session_id", UUID.class),
            rs.getString("token_hash"),
            rs.getObject("user_id", UUID.class),
            rs.getLong("tenant_id"),
            rs.getString("csrf_token"),
            rs.getString("remote_addr"),
            instant(rs, "created_at"),
            instant(rs, "last_seen_at"),
            instant(rs, "idle_expires_at"),
            instant(rs, "absolute_expires_at"),
            instant(rs, "revoked_at"));

    private final JdbcTemplate jdbc;

    public OpsSessionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID sessionId, String tokenHash, UUID userId, long tenantId,
                       String csrfToken, String remoteAddr, Instant idleExpires,
                       Instant absoluteExpires) {
        jdbc.update("""
                INSERT INTO ops_session (session_id, token_hash, user_id, tenant_id, csrf_token,
                    remote_addr, idle_expires_at, absolute_expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, sessionId, tokenHash, userId, tenantId, csrfToken, remoteAddr,
                Timestamp.from(idleExpires), Timestamp.from(absoluteExpires));
    }

    public Optional<OpsSession> findByTokenHash(String tokenHash) {
        List<OpsSession> rows = jdbc.query("SELECT " + COLUMNS + " FROM ops_session"
                + " WHERE token_hash = ?", MAPPER, tokenHash);
        return rows.stream().findFirst();
    }

    public Optional<OpsSession> find(UUID sessionId) {
        List<OpsSession> rows = jdbc.query("SELECT " + COLUMNS + " FROM ops_session"
                + " WHERE session_id = ?", MAPPER, sessionId);
        return rows.stream().findFirst();
    }

    public List<OpsSession> activeForUser(long tenantId, UUID userId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM ops_session"
                        + " WHERE tenant_id = ? AND user_id = ? AND revoked_at IS NULL"
                        + " ORDER BY last_seen_at DESC", MAPPER, tenantId, userId);
    }

    public void touch(UUID sessionId, Instant idleExpires) {
        jdbc.update("UPDATE ops_session SET last_seen_at = now(), idle_expires_at = ?"
                + " WHERE session_id = ? AND revoked_at IS NULL", Timestamp.from(idleExpires), sessionId);
    }

    public void revoke(UUID sessionId) {
        jdbc.update("UPDATE ops_session SET revoked_at = now()"
                + " WHERE session_id = ? AND revoked_at IS NULL", sessionId);
    }

    /** @return the number of sessions ended */
    public int revokeAllForUser(long tenantId, UUID userId, UUID exceptSessionId) {
        if (exceptSessionId == null) {
            return jdbc.update("UPDATE ops_session SET revoked_at = now()"
                    + " WHERE tenant_id = ? AND user_id = ? AND revoked_at IS NULL",
                    tenantId, userId);
        }
        return jdbc.update("UPDATE ops_session SET revoked_at = now()"
                        + " WHERE tenant_id = ? AND user_id = ? AND revoked_at IS NULL"
                        + " AND session_id <> ?",
                tenantId, userId, exceptSessionId);
    }

    public int deleteExpiredBefore(Instant cutoff) {
        return jdbc.update("DELETE FROM ops_session WHERE absolute_expires_at < ?",
                Timestamp.from(cutoff));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
