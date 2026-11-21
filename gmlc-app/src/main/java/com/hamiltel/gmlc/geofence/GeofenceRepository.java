package com.hamiltel.gmlc.geofence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Geofence definitions, arms and the events derived from stored results.
 * Geometry is compared in the application, not in the database: the shape
 * parameters are the source of truth and every evaluation uses the same code.
 */
@Repository
public class GeofenceRepository {

    private static final String VIEW = """
            SELECT g.geofence_id, g.name, g.description, g.group_name, g.shape, g.geometry,
                   g.report_inside, g.enabled, g.created_at, g.updated_at,
                   (SELECT count(*) FROM geofence_arm a
                    WHERE a.geofence_id = g.geofence_id) AS armed,
                   (SELECT count(*) FROM geofence_arm a
                    WHERE a.geofence_id = g.geofence_id AND a.state = 'INSIDE') AS inside
            FROM geofence g
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final RowMapper<GeofenceView> geofenceMapper;

    public GeofenceRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.geofenceMapper = (rs, rowNum) -> new GeofenceView(
                rs.getObject("geofence_id", UUID.class),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("group_name"),
                rs.getString("shape"),
                geometry(rs.getString("geometry")),
                rs.getBoolean("report_inside"),
                rs.getBoolean("enabled"),
                rs.getLong("armed"),
                rs.getLong("inside"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    public void insert(long tenantId, UUID geofenceId, String name, String description,
                       String group, GeofenceGeometry geometry, boolean reportInside,
                       UUID createdBy) {
        jdbc.update("""
                INSERT INTO geofence (geofence_id, tenant_id, name, description, group_name,
                    shape, geometry, report_inside, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                """,
                geofenceId, tenantId, name, description, group, geometry.shapeName(),
                json(geometry.toJson()), reportInside, createdBy);
    }

    public void update(long tenantId, UUID geofenceId, String name, String description,
                       String group, boolean reportInside, boolean enabled) {
        jdbc.update("""
                UPDATE geofence
                SET name = ?, description = ?, group_name = ?, report_inside = ?, enabled = ?,
                    updated_at = now()
                WHERE tenant_id = ? AND geofence_id = ?
                """,
                name, description, group, reportInside, enabled, tenantId, geofenceId);
    }

    public List<GeofenceView> list(long tenantId) {
        return jdbc.query(VIEW + " WHERE g.tenant_id = ? ORDER BY g.name",
                geofenceMapper, tenantId);
    }

    public Optional<GeofenceView> find(long tenantId, UUID geofenceId) {
        return jdbc.query(VIEW + " WHERE g.tenant_id = ? AND g.geofence_id = ?",
                geofenceMapper, tenantId, geofenceId).stream().findFirst();
    }

    public boolean exists(long tenantId, String name) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM geofence WHERE tenant_id = ? AND lower(name) = lower(?)",
                Integer.class, tenantId, name);
        return count != null && count > 0;
    }

    public boolean existsOther(long tenantId, UUID geofenceId, String name) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM geofence
                WHERE tenant_id = ? AND geofence_id <> ? AND lower(name) = lower(?)
                """, Integer.class, tenantId, geofenceId, name);
        return count != null && count > 0;
    }

    // ------------------------------------------------------------------ arms

    public boolean arm(UUID geofenceId, long tenantId, String subjectRef, String subjectLabel,
                       UUID armedBy) {
        return jdbc.update("""
                INSERT INTO geofence_arm (geofence_id, tenant_id, subject_ref, subject_label,
                    armed_by)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (geofence_id, subject_ref) DO NOTHING
                """, geofenceId, tenantId, subjectRef, subjectLabel, armedBy) == 1;
    }

    public boolean disarm(UUID geofenceId, long tenantId, String subjectRef) {
        return jdbc.update(
                "DELETE FROM geofence_arm WHERE tenant_id = ? AND geofence_id = ? AND subject_ref = ?",
                tenantId, geofenceId, subjectRef) == 1;
    }

    public List<ArmView> arms(long tenantId, UUID geofenceId) {
        return jdbc.query("""
                SELECT a.geofence_id, g.name AS geofence_name, a.subject_ref, a.subject_label,
                       a.state, a.armed_at, a.last_evaluated_at, a.last_result_id
                FROM geofence_arm a
                JOIN geofence g ON g.geofence_id = a.geofence_id
                WHERE a.tenant_id = ? AND a.geofence_id = ?
                ORDER BY a.subject_label
                """, ARM_MAPPER, tenantId, geofenceId);
    }

    public List<ArmView> armsByState(long tenantId, String state, int limit) {
        return jdbc.query("""
                SELECT a.geofence_id, g.name AS geofence_name, a.subject_ref, a.subject_label,
                       a.state, a.armed_at, a.last_evaluated_at, a.last_result_id
                FROM geofence_arm a
                JOIN geofence g ON g.geofence_id = a.geofence_id
                WHERE a.tenant_id = ? AND a.state = ? AND g.enabled = TRUE
                ORDER BY a.last_evaluated_at DESC NULLS LAST
                LIMIT ?
                """, ARM_MAPPER, tenantId, state, limit);
    }

    /**
     * The arms a stored result can affect: enabled geofences on this tenant
     * that carry this pseudonymous subject. Disabled geofences are skipped,
     * but their arm and history are kept.
     */
    public List<ArmedGeofence> armedGeofences(long tenantId, String subjectRef) {
        return jdbc.query("""
                SELECT g.geofence_id, g.name, g.geometry, g.report_inside, a.state,
                       a.subject_label
                FROM geofence_arm a
                JOIN geofence g ON g.geofence_id = a.geofence_id
                WHERE a.tenant_id = ? AND a.subject_ref = ? AND g.enabled = TRUE
                """,
                (rs, rowNum) -> new ArmedGeofence(
                        rs.getObject("geofence_id", UUID.class),
                        rs.getString("name"),
                        geometry(rs.getString("geometry")),
                        rs.getBoolean("report_inside"),
                        rs.getString("state"),
                        rs.getString("subject_label")),
                tenantId, subjectRef);
    }

    /**
     * Writes the new state and, when the state changed, the event. The unique
     * key on (geofence, subject, result, type) makes a repeated evaluation of
     * the same result harmless.
     */
    public Long recordEvaluation(long tenantId, UUID geofenceId, String subjectRef,
                                 String subjectLabel, String previousState, boolean inside,
                                 boolean reportInside, UUID resultId, UUID requestId,
                                 Instant occurredAt, double latitude, double longitude,
                                 int uncertaintyM, String method) {
        jdbc.update("""
                UPDATE geofence_arm
                SET state = ?, last_evaluated_at = ?, last_result_id = ?
                WHERE tenant_id = ? AND geofence_id = ? AND subject_ref = ?
                """,
                inside ? "INSIDE" : "OUTSIDE", Timestamp.from(Instant.now()), resultId,
                tenantId, geofenceId, subjectRef);

        String eventType = eventType(previousState, inside, reportInside);
        if (eventType == null) {
            return null;
        }
        List<Long> ids = jdbc.query("""
                INSERT INTO geofence_event (tenant_id, geofence_id, subject_ref, subject_label,
                    event_type, occurred_at, result_id, request_id, latitude, longitude,
                    uncertainty_m, method)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (geofence_id, subject_ref, result_id, event_type) DO NOTHING
                RETURNING event_id
                """, (rs, rowNum) -> rs.getLong("event_id"),
                tenantId, geofenceId, subjectRef, subjectLabel, eventType,
                Timestamp.from(occurredAt), resultId, requestId, latitude, longitude,
                uncertaintyM, method);
        return ids.isEmpty() ? null : ids.get(0);
    }

    static String eventType(String previousState, boolean inside, boolean reportInside) {
        if (previousState == null) {
            return inside ? "ENTER" : null;
        }
        if ("OUTSIDE".equals(previousState)) {
            return inside ? "ENTER" : null;
        }
        if (inside) {
            return reportInside ? "INSIDE" : null;
        }
        return "LEAVE";
    }

    // ---------------------------------------------------------------- events

    public long eventCount(long tenantId, Instant from, Instant to, String type,
                           UUID geofenceId) {
        Filters filters = filters(type, geofenceId);
        Long count = jdbc.queryForObject("SELECT count(*) FROM geofence_event e WHERE e.tenant_id = ?"
                        + " AND e.occurred_at >= ? AND e.occurred_at < ?" + filters.sql,
                Long.class, concat(new Object[]{tenantId, Timestamp.from(from),
                        Timestamp.from(to)}, filters.args));
        return count == null ? 0 : count;
    }

    public List<EventRow> events(long tenantId, Instant from, Instant to, String type,
                                 UUID geofenceId, int limit, int offset) {
        Filters filters = filters(type, geofenceId);
        Object[] args = concat(new Object[]{tenantId, Timestamp.from(from),
                Timestamp.from(to)}, filters.args);
        args = concat(args, new Object[]{limit, offset});
        return jdbc.query("""
                SELECT e.event_id, e.geofence_id, g.name AS geofence_name, e.subject_label,
                       e.event_type, e.occurred_at, e.result_id, e.request_id, e.latitude,
                       e.longitude, e.uncertainty_m, e.method
                FROM geofence_event e
                JOIN geofence g ON g.geofence_id = e.geofence_id
                WHERE e.tenant_id = ? AND e.occurred_at >= ? AND e.occurred_at < ?
                """ + filters.sql + " ORDER BY e.occurred_at DESC, e.event_id DESC LIMIT ? OFFSET ?",
                EVENT_MAPPER, args);
    }

    public Summary summary(long tenantId, Instant from, Instant to) {
        SummaryRow row = jdbc.queryForObject("""
                SELECT
                  (SELECT count(*) FROM geofence WHERE tenant_id = ?) AS total,
                  (SELECT count(*) FROM geofence WHERE tenant_id = ? AND enabled) AS enabled,
                  (SELECT count(*) FROM geofence_arm WHERE tenant_id = ?) AS armed,
                  (SELECT count(*) FROM geofence_arm
                   WHERE tenant_id = ? AND state = 'INSIDE') AS inside,
                  (SELECT count(*) FROM geofence_event
                   WHERE tenant_id = ? AND occurred_at >= ? AND occurred_at < ?) AS events,
                  (SELECT count(*) FROM geofence_event
                   WHERE tenant_id = ? AND occurred_at >= ? AND occurred_at < ?
                     AND event_type = 'ENTER') AS entering,
                  (SELECT count(*) FROM geofence_event
                   WHERE tenant_id = ? AND occurred_at >= ? AND occurred_at < ?
                     AND event_type = 'LEAVE') AS leaving,
                  (SELECT count(*) FROM geofence_event
                   WHERE tenant_id = ? AND occurred_at >= ? AND occurred_at < ?
                     AND event_type = 'INSIDE') AS inside_events
                """, (rs, rowNum) -> new SummaryRow(
                        rs.getLong("total"), rs.getLong("enabled"), rs.getLong("armed"),
                        rs.getLong("inside"), rs.getLong("events"), rs.getLong("entering"),
                        rs.getLong("leaving"), rs.getLong("inside_events")),
                tenantId, tenantId, tenantId, tenantId,
                tenantId, Timestamp.from(from), Timestamp.from(to),
                tenantId, Timestamp.from(from), Timestamp.from(to),
                tenantId, Timestamp.from(from), Timestamp.from(to),
                tenantId, Timestamp.from(from), Timestamp.from(to));
        return new Summary(row.total(), row.enabled(), row.armed(), row.inside(), row.events(),
                row.entering(), row.leaving(), row.insideEvents());
    }

    // --------------------------------------------------------------- mapping

    private static final RowMapper<ArmView> ARM_MAPPER = (rs, rowNum) -> new ArmView(
            rs.getObject("geofence_id", UUID.class),
            rs.getString("geofence_name"),
            rs.getString("subject_ref"),
            rs.getString("subject_label"),
            rs.getString("state"),
            rs.getTimestamp("armed_at") == null ? null
                    : rs.getTimestamp("armed_at").toInstant(),
            rs.getTimestamp("last_evaluated_at") == null ? null
                    : rs.getTimestamp("last_evaluated_at").toInstant(),
            rs.getObject("last_result_id", UUID.class));

    private static final RowMapper<EventRow> EVENT_MAPPER = (rs, rowNum) -> new EventRow(
            rs.getLong("event_id"),
            rs.getObject("geofence_id", UUID.class),
            rs.getString("geofence_name"),
            rs.getString("subject_label"),
            rs.getString("event_type"),
            rs.getTimestamp("occurred_at").toInstant(),
            rs.getObject("result_id", UUID.class),
            rs.getObject("request_id", UUID.class),
            rs.getBigDecimal("latitude").doubleValue(),
            rs.getBigDecimal("longitude").doubleValue(),
            rs.getInt("uncertainty_m"),
            rs.getString("method"));

    private JsonNode geometry(String json) {
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored geofence geometry is not valid JSON", e);
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("geofence geometry could not be serialised", e);
        }
    }

    private static Filters filters(String type, UUID geofenceId) {
        StringBuilder sql = new StringBuilder();
        List<Object> args = new java.util.ArrayList<>(2);
        if (type != null && !type.isBlank()) {
            sql.append(" AND e.event_type = ?");
            args.add(type);
        }
        if (geofenceId != null) {
            sql.append(" AND e.geofence_id = ?");
            args.add(geofenceId);
        }
        return new Filters(sql.toString(), args.toArray());
    }

    private static Object[] concat(Object[] first, Object[] second) {
        Object[] combined = new Object[first.length + second.length];
        System.arraycopy(first, 0, combined, 0, first.length);
        System.arraycopy(second, 0, combined, first.length, second.length);
        return combined;
    }

    // --------------------------------------------------------------- records

    public record GeofenceView(UUID geofenceId, String name, String description, String group,
                               String shape, JsonNode geometry, boolean reportInside,
                               boolean enabled, long armed, long inside, Instant createdAt,
                               Instant updatedAt) {
    }

    public record ArmView(UUID geofenceId, String geofenceName, String subjectRef,
                          String subjectLabel, String state, Instant armedAt,
                          Instant lastEvaluatedAt, UUID lastResultId) {
    }

    public record EventRow(long eventId, UUID geofenceId, String geofenceName,
                           String subjectLabel, String eventType, Instant occurredAt,
                           UUID resultId, UUID requestId, double latitude, double longitude,
                           int uncertaintyM, String method) {
    }

    public record ArmedGeofence(UUID geofenceId, String name, JsonNode geometry,
                                boolean reportInside, String state, String subjectLabel) {
    }

    public record Summary(long total, long enabled, long armed, long inside, long events,
                          long entering, long leaving, long insideEvents) {
    }

    private record SummaryRow(long total, long enabled, long armed, long inside, long events,
                              long entering, long leaving, long insideEvents) {
    }

    private record Filters(String sql, Object[] args) {
    }
}
