package com.hamiltel.gmlc.config;

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
public class ConfigRepository {

    public record ItemRow(String itemKey, String valueJson, int version, UUID updatedBy,
                          String updatedByName, Instant updatedAt) {
    }

    public record ChangeRow(UUID changeId, String itemKey, int baseVersion, String previousJson,
                            String requestedJson, String reason, String status,
                            boolean requiresApproval, UUID requestedBy, String requestedByName,
                            Instant requestedAt, UUID decidedBy, String decidedByName,
                            Instant decidedAt, String decisionNote, Integer appliedVersion,
                            Instant appliedAt) {
    }

    private static final String CHANGE_SELECT = """
            SELECT c.change_id, c.item_key, c.base_version, c.previous_value::text AS previous_json,
                   c.requested_value::text AS requested_json, c.reason, c.status,
                   c.requires_approval, c.requested_by, ru.display_name AS requested_by_name,
                   c.requested_at, c.decided_by, du.display_name AS decided_by_name,
                   c.decided_at, c.decision_note, c.applied_version, c.applied_at
            FROM config_change c
            JOIN ops_user ru ON ru.user_id = c.requested_by
            LEFT JOIN ops_user du ON du.user_id = c.decided_by
            """;

    private static final RowMapper<ChangeRow> CHANGE_MAPPER = (rs, rowNum) -> new ChangeRow(
            rs.getObject("change_id", UUID.class),
            rs.getString("item_key"),
            rs.getInt("base_version"),
            rs.getString("previous_json"),
            rs.getString("requested_json"),
            rs.getString("reason"),
            rs.getString("status"),
            rs.getBoolean("requires_approval"),
            rs.getObject("requested_by", UUID.class),
            rs.getString("requested_by_name"),
            instant(rs, "requested_at"),
            rs.getObject("decided_by", UUID.class),
            rs.getString("decided_by_name"),
            instant(rs, "decided_at"),
            rs.getString("decision_note"),
            rs.getObject("applied_version", Integer.class),
            instant(rs, "applied_at"));

    private static final RowMapper<ItemRow> ITEM_MAPPER = (rs, rowNum) -> new ItemRow(
            rs.getString("item_key"),
            rs.getString("value_json"),
            rs.getInt("version"),
            rs.getObject("updated_by", UUID.class),
            rs.getString("updated_by_name"),
            instant(rs, "updated_at"));

    private final JdbcTemplate jdbc;

    public ConfigRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ItemRow> items(long tenantId) {
        return jdbc.query("""
                SELECT i.item_key, i.value::text AS value_json, i.version, i.updated_by,
                       u.display_name AS updated_by_name, i.updated_at
                FROM config_item i
                LEFT JOIN ops_user u ON u.user_id = i.updated_by
                WHERE i.tenant_id = ?
                """, ITEM_MAPPER, tenantId);
    }

    public Optional<ItemRow> item(long tenantId, String key) {
        return jdbc.query("""
                SELECT i.item_key, i.value::text AS value_json, i.version, i.updated_by,
                       u.display_name AS updated_by_name, i.updated_at
                FROM config_item i
                LEFT JOIN ops_user u ON u.user_id = i.updated_by
                WHERE i.tenant_id = ? AND i.item_key = ?
                """, ITEM_MAPPER, tenantId, key).stream().findFirst();
    }

    public int version(long tenantId, String key) {
        return item(tenantId, key).map(ItemRow::version).orElse(0);
    }

    public List<ChangeRow> changes(long tenantId, String status, int limit) {
        if (status == null) {
            return jdbc.query(CHANGE_SELECT + " WHERE c.tenant_id = ?"
                    + " ORDER BY c.requested_at DESC LIMIT ?", CHANGE_MAPPER, tenantId, limit);
        }
        return jdbc.query(CHANGE_SELECT + " WHERE c.tenant_id = ? AND c.status = ?"
                + " ORDER BY c.requested_at DESC LIMIT ?", CHANGE_MAPPER, tenantId, status, limit);
    }

    public Optional<ChangeRow> change(long tenantId, UUID changeId) {
        return jdbc.query(CHANGE_SELECT + " WHERE c.tenant_id = ? AND c.change_id = ?",
                CHANGE_MAPPER, tenantId, changeId).stream().findFirst();
    }

    public Optional<ChangeRow> pending(long tenantId, String key) {
        return jdbc.query(CHANGE_SELECT + " WHERE c.tenant_id = ? AND c.item_key = ?"
                        + " AND c.status = 'PENDING'", CHANGE_MAPPER, tenantId, key)
                .stream().findFirst();
    }

    public void insertChange(UUID changeId, long tenantId, String key, int baseVersion,
                             String previousJson, String requestedJson, String reason,
                             boolean requiresApproval, UUID requestedBy, Instant requestedAt) {
        jdbc.update("""
                INSERT INTO config_change (change_id, tenant_id, item_key, base_version,
                    previous_value, requested_value, reason, status, requires_approval,
                    requested_by, requested_at)
                VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, 'PENDING', ?, ?, ?)
                """, changeId, tenantId, key, baseVersion, previousJson, requestedJson, reason,
                requiresApproval, requestedBy, Timestamp.from(requestedAt));
    }

    /** @return false when the row moved and the base version is no longer current */
    public boolean upsertItem(long tenantId, String key, String valueJson, int expectedVersion,
                              int newVersion, UUID updatedBy) {
        int rows = jdbc.update("""
                INSERT INTO config_item (tenant_id, item_key, value, version, updated_by, updated_at)
                VALUES (?, ?, ?::jsonb, ?, ?, now())
                ON CONFLICT (tenant_id, item_key) DO UPDATE
                SET value = excluded.value, version = excluded.version,
                    updated_by = excluded.updated_by, updated_at = now()
                WHERE config_item.version = ?
                """, tenantId, key, valueJson, newVersion, updatedBy, expectedVersion);
        return rows == 1;
    }

    public boolean decide(UUID changeId, long tenantId, String status, UUID decidedBy,
                          String note, Integer appliedVersion, Instant decidedAt) {
        int rows = jdbc.update("""
                UPDATE config_change
                SET status = ?, decided_by = ?, decided_at = ?, decision_note = ?,
                    applied_version = ?, applied_at = ?
                WHERE change_id = ? AND tenant_id = ? AND status = 'PENDING'
                """, status, decidedBy, Timestamp.from(decidedAt), note,
                appliedVersion, appliedVersion == null ? null : Timestamp.from(decidedAt),
                changeId, tenantId);
        return rows == 1;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
