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
public class OpsUserRepository {

    private static final String COLUMNS = """
            user_id, tenant_id, username, display_name, role, password_hash, totp_secret,
            enabled, failed_attempts, locked_until, last_login_at, created_at
            """;

    private static final RowMapper<OpsUser> MAPPER = (rs, rowNum) -> new OpsUser(
            rs.getObject("user_id", UUID.class),
            rs.getLong("tenant_id"),
            rs.getString("username"),
            rs.getString("display_name"),
            OpsRole.valueOf(rs.getString("role")),
            rs.getString("password_hash"),
            rs.getString("totp_secret"),
            rs.getBoolean("enabled"),
            rs.getInt("failed_attempts"),
            instant(rs, "locked_until"),
            instant(rs, "last_login_at"),
            instant(rs, "created_at"));

    private final JdbcTemplate jdbc;

    public OpsUserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<OpsUser> findByUsername(long tenantId, String username) {
        List<OpsUser> rows = jdbc.query("SELECT " + COLUMNS + " FROM ops_user"
                + " WHERE tenant_id = ? AND username = ?", MAPPER, tenantId, username);
        return rows.stream().findFirst();
    }

    public Optional<OpsUser> find(long tenantId, UUID userId) {
        List<OpsUser> rows = jdbc.query("SELECT " + COLUMNS + " FROM ops_user"
                + " WHERE tenant_id = ? AND user_id = ?", MAPPER, tenantId, userId);
        return rows.stream().findFirst();
    }

    public List<OpsUser> list(long tenantId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM ops_user WHERE tenant_id = ?"
                + " ORDER BY username", MAPPER, tenantId);
    }

    public long count(long tenantId) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM ops_user WHERE tenant_id = ?",
                Long.class, tenantId);
        return count == null ? 0 : count;
    }

    public long countEnabledAdmins(long tenantId) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM ops_user"
                        + " WHERE tenant_id = ? AND role = 'ADMIN' AND enabled",
                Long.class, tenantId);
        return count == null ? 0 : count;
    }

    public void insert(UUID userId, long tenantId, String username, String displayName, OpsRole role,
                       String passwordHash, String totpSecret, boolean enabled) {
        jdbc.update("""
                INSERT INTO ops_user (user_id, tenant_id, username, display_name, role,
                    password_hash, totp_secret, enabled)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, userId, tenantId, username, displayName, role.name(), passwordHash,
                totpSecret, enabled);
    }

    public void updateRole(long tenantId, UUID userId, OpsRole role) {
        jdbc.update("UPDATE ops_user SET role = ? WHERE tenant_id = ? AND user_id = ?",
                role.name(), tenantId, userId);
    }

    public void setEnabled(long tenantId, UUID userId, boolean enabled) {
        jdbc.update("UPDATE ops_user SET enabled = ?, failed_attempts = 0, locked_until = NULL"
                + " WHERE tenant_id = ? AND user_id = ?", enabled, tenantId, userId);
    }

    public void changePassword(long tenantId, UUID userId, String passwordHash) {
        jdbc.update("UPDATE ops_user SET password_hash = ?, password_changed_at = now(),"
                + " failed_attempts = 0, locked_until = NULL WHERE tenant_id = ? AND user_id = ?",
                passwordHash, tenantId, userId);
    }

    /**
     * One failed attempt, and a lock when the count reaches the threshold. The
     * counter and the lock move in the same statement so a crash cannot leave
     * an unlocked account with a full counter.
     */
    public void recordFailure(long tenantId, UUID userId, int threshold, long lockMinutes) {
        jdbc.update("""
                UPDATE ops_user
                SET failed_attempts = failed_attempts + 1,
                    locked_until = CASE WHEN failed_attempts + 1 >= ?
                                        THEN now() + (? * interval '1 minute')
                                        ELSE locked_until END
                WHERE tenant_id = ? AND user_id = ?
                """, threshold, lockMinutes, tenantId, userId);
    }

    public void recordSuccess(long tenantId, UUID userId) {
        jdbc.update("""
                UPDATE ops_user
                SET failed_attempts = 0, locked_until = NULL, last_login_at = now()
                WHERE tenant_id = ? AND user_id = ?
                """, tenantId, userId);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
