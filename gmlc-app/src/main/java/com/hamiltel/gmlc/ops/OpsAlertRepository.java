package com.hamiltel.gmlc.ops;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Stored alerts. One row per open condition: a repeated evaluation refreshes
 * the row instead of multiplying it, so a storm is one alert with a
 * {@code lastSeenAt} that keeps moving. Closing is done by the engine when the
 * condition is no longer observed - never by a person.
 */
@Repository
public class OpsAlertRepository {

    /** The SQL error that means the partial unique index refused a duplicate. */
    private static final String UNIQUE_VIOLATION = "23505";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public OpsAlertRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void raise(long tenantId, String dedupeKey, Kind kind, Severity severity,
                      String summary, Map<String, Object> detail) {
        String json = toJson(detail);
        try {
            jdbc.update("""
                    INSERT INTO ops_alert (tenant_id, dedupe_key, kind, severity, summary, detail)
                    VALUES (?, ?, ?, ?, ?, ?::jsonb)
                    ON CONFLICT (tenant_id, dedupe_key) WHERE closed_at IS NULL
                    DO UPDATE SET last_seen_at = now(), severity = EXCLUDED.severity,
                                  summary = EXCLUDED.summary, detail = EXCLUDED.detail
                    """, tenantId, dedupeKey, kind.name(), severity.name(), summary, json);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // Two evaluators raced on the same new condition; the other row is
            // the alert we wanted. Retry once as an update.
            if (!isUniqueViolation(e)) {
                throw e;
            }
            jdbc.update("""
                    UPDATE ops_alert SET last_seen_at = now(), severity = ?, summary = ?,
                           detail = ?::jsonb
                    WHERE tenant_id = ? AND dedupe_key = ? AND closed_at IS NULL
                    """, severity.name(), summary, json, tenantId, dedupeKey);
        }
    }

    /** Closes every open alert whose key was not raised in this evaluation. */
    public void closeExcept(long tenantId, Collection<String> keys) {
        if (keys.isEmpty()) {
            jdbc.update("UPDATE ops_alert SET closed_at = now()"
                    + " WHERE tenant_id = ? AND closed_at IS NULL", tenantId);
            return;
        }
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.addAll(keys);
        String placeholders = String.join(", ", keys.stream().map(key -> "?").toList());
        jdbc.update("UPDATE ops_alert SET closed_at = now()"
                + " WHERE tenant_id = ? AND closed_at IS NULL"
                + " AND dedupe_key NOT IN (" + placeholders + ")", args.toArray());
    }

    public List<AlertRow> list(long tenantId, State state, int limit) {
        String where = switch (state) {
            case OPEN -> " AND closed_at IS NULL";
            case ACKNOWLEDGED -> " AND closed_at IS NULL AND acknowledged_at IS NOT NULL";
            case CLOSED -> " AND closed_at IS NOT NULL";
            case ALL -> "";
        };
        return jdbc.query("SELECT alert_id, dedupe_key, kind, severity, summary, detail::text AS detail,"
                        + " opened_at, last_seen_at, closed_at, acknowledged_at, acknowledged_by"
                        + " FROM ops_alert WHERE tenant_id = ?" + where
                        + " ORDER BY opened_at DESC, alert_id DESC LIMIT ?",
                MAPPER, tenantId, limit);
    }

    public Optional<AlertRow> find(long tenantId, long alertId) {
        return jdbc.query("SELECT alert_id, dedupe_key, kind, severity, summary, detail::text AS detail,"
                        + " opened_at, last_seen_at, closed_at, acknowledged_at, acknowledged_by"
                        + " FROM ops_alert WHERE tenant_id = ? AND alert_id = ?",
                MAPPER, tenantId, alertId).stream().findFirst();
    }

    /** @return false when the row was closed or already acknowledged */
    public boolean acknowledge(long tenantId, long alertId, UUID userId) {
        int rows = jdbc.update("UPDATE ops_alert SET acknowledged_at = now(), acknowledged_by = ?"
                + " WHERE tenant_id = ? AND alert_id = ? AND closed_at IS NULL"
                + " AND acknowledged_at IS NULL", userId, tenantId, alertId);
        return rows == 1;
    }

    public long openCount(long tenantId) {
        return count("SELECT count(*) FROM ops_alert WHERE tenant_id = ? AND closed_at IS NULL",
                tenantId);
    }

    public long acknowledgedOpenCount(long tenantId) {
        return count("SELECT count(*) FROM ops_alert WHERE tenant_id = ? AND closed_at IS NULL"
                + " AND acknowledged_at IS NOT NULL", tenantId);
    }

    private long count(String sql, long tenantId) {
        Long value = jdbc.queryForObject(sql, Long.class, tenantId);
        return value == null ? 0 : value;
    }

    private static boolean isUniqueViolation(org.springframework.dao.DataIntegrityViolationException e) {
        Throwable cause = e.getMostSpecificCause();
        return cause instanceof java.sql.SQLException sql && UNIQUE_VIOLATION.equals(sql.getSQLState());
    }

    private String toJson(Map<String, Object> detail) {
        if (detail == null || detail.isEmpty()) {
            return null;
        }
        try {
            return mapper.writeValueAsString(detail);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static final RowMapper<AlertRow> MAPPER = (rs, n) -> new AlertRow(
            rs.getLong("alert_id"),
            rs.getString("dedupe_key"),
            Kind.valueOf(rs.getString("kind")),
            Severity.valueOf(rs.getString("severity")),
            rs.getString("summary"),
            rs.getString("detail"),
            instant(rs, "opened_at"),
            instant(rs, "last_seen_at"),
            instant(rs, "closed_at"),
            instant(rs, "acknowledged_at"),
            rs.getObject("acknowledged_by", UUID.class));

    private static Instant instant(java.sql.ResultSet rs, String column)
            throws java.sql.SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    public enum Kind {
        PEER_DOWN,
        ERROR_RATE,
        LATENCY_SLO,
        CELL_DB_GAP
    }

    public enum Severity {
        INFO,
        WARNING,
        CRITICAL
    }

    public enum State {
        OPEN,
        ACKNOWLEDGED,
        CLOSED,
        ALL
    }

    /** {@code detailJson} is kept as text so the controller can hand Jackson a tree. */
    public record AlertRow(long alertId, String dedupeKey, Kind kind, Severity severity,
                           String summary, String detailJson, Instant openedAt,
                           Instant lastSeenAt, Instant closedAt, Instant acknowledgedAt,
                           UUID acknowledgedBy) {

        public JsonNode detail(ObjectMapper mapper) {
            if (detailJson == null || detailJson.isBlank()) {
                return null;
            }
            try {
                return mapper.readTree(detailJson);
            } catch (JsonProcessingException e) {
                return null;
            }
        }
    }
}
