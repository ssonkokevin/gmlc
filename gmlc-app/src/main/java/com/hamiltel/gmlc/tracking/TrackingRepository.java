package com.hamiltel.gmlc.tracking;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The tracking roster and the trail it resolves to.
 *
 * <p>A trail row is never created here: it is a stored result already written
 * by the governed request path. The roster holds the identifier only so the
 * join can find those requests; no view carries it, only the masked label.
 */
@Repository
public class TrackingRepository {

    private static final String FIX_COLUMNS = """
            f.request_id, f.result_id, f.method, f.methods_attempted, f.shape,
            f.latitude, f.longitude, f.uncertainty_m, f.confidence, f.age_seconds,
            f.accuracy_requested_m, f.accuracy_fulfilled, f.serving_cell, f.engine,
            f.positioning_node, f.produced_at
            """;

    private static final String TRACKED_COLUMNS = """
            SELECT t.tracked_id, t.client_id, c.name AS client_name, t.subject_label, t.purpose,
            t.enabled, t.created_at, t.updated_at,
            (SELECT count(*) FROM location_request r0
              WHERE r0.tenant_id = t.tenant_id AND r0.client_id = t.client_id
                AND r0.purpose = t.purpose AND r0.result_id IS NOT NULL
                AND ((t.subject_msisdn IS NOT NULL
                      AND r0.subscriber_msisdn = t.subject_msisdn)
                  OR (t.subject_imsi IS NOT NULL
                      AND r0.subscriber_imsi = t.subject_imsi))) AS fixes,
            """ + FIX_COLUMNS + """

            FROM tracked_subject t
            JOIN api_client c ON c.client_id = t.client_id
            LEFT JOIN LATERAL (
                SELECT r.request_id, lr.result_id, lr.method, lr.methods_attempted, lr.shape,
                       lr.latitude, lr.longitude, lr.uncertainty_m, lr.confidence,
                       lr.age_seconds, lr.accuracy_requested_m, lr.accuracy_fulfilled,
                       lr.serving_cell, lr.engine, lr.positioning_node, lr.produced_at
                FROM location_request r
                JOIN location_result lr
                  ON lr.tenant_id = r.tenant_id AND lr.request_id = r.request_id
                WHERE r.tenant_id = t.tenant_id AND r.client_id = t.client_id
                  AND r.purpose = t.purpose
                  AND ((t.subject_msisdn IS NOT NULL
                        AND r.subscriber_msisdn = t.subject_msisdn)
                    OR (t.subject_imsi IS NOT NULL
                        AND r.subscriber_imsi = t.subject_imsi))
                ORDER BY lr.produced_at DESC, lr.result_id DESC
                LIMIT 1
            ) f ON TRUE
            """;

    private static final String TRAIL = """
            SELECT r.request_id, lr.result_id, lr.method, lr.methods_attempted, lr.shape,
                   lr.latitude, lr.longitude, lr.uncertainty_m, lr.confidence, lr.age_seconds,
                   lr.accuracy_requested_m, lr.accuracy_fulfilled, lr.serving_cell, lr.engine,
                   lr.positioning_node, lr.produced_at
            FROM tracked_subject t
            JOIN location_request r
              ON r.tenant_id = t.tenant_id AND r.client_id = t.client_id
             AND r.purpose = t.purpose
             AND ((t.subject_msisdn IS NOT NULL AND r.subscriber_msisdn = t.subject_msisdn)
               OR (t.subject_imsi IS NOT NULL AND r.subscriber_imsi = t.subject_imsi))
            JOIN location_result lr
              ON lr.tenant_id = r.tenant_id AND lr.request_id = r.request_id
            WHERE t.tenant_id = ? AND t.tracked_id = ?
            ORDER BY lr.produced_at DESC, lr.result_id DESC
            LIMIT ?
            """;

    private final JdbcTemplate jdbc;

    public TrackingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return false when this client already tracks the subject for this purpose */
    public boolean insert(UUID trackedId, long tenantId, UUID clientId, String subjectRef,
                          String msisdn, String imsi, String label, String purpose,
                          UUID createdBy) {
        return jdbc.update("""
                INSERT INTO tracked_subject (tracked_id, tenant_id, client_id, subject_ref,
                    subject_msisdn, subject_imsi, subject_label, purpose, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, client_id, subject_ref, purpose) DO NOTHING
                """, trackedId, tenantId, clientId, subjectRef, msisdn, imsi, label, purpose,
                createdBy) == 1;
    }

    public List<TrackedView> list(long tenantId) {
        return jdbc.query(TRACKED_COLUMNS
                        + " WHERE t.tenant_id = ? ORDER BY t.subject_label, t.purpose",
                TRACKED_MAPPER, tenantId);
    }

    public Optional<TrackedView> find(long tenantId, UUID trackedId) {
        return jdbc.query(TRACKED_COLUMNS + " WHERE t.tenant_id = ? AND t.tracked_id = ?",
                TRACKED_MAPPER, tenantId, trackedId).stream().findFirst();
    }

    /**
     * The client-facing view: who owns the roster row, whether it is enabled,
     * the reference consent is checked against and the latest fix. Deliberately
     * no label and no identifier.
     */
    public Optional<OwnedSubject> findOwner(long tenantId, UUID clientId, UUID trackedId) {
        return jdbc.query("""
                SELECT t.tracked_id, t.client_id, t.subject_ref, t.purpose, t.enabled,
                       f.request_id, f.result_id, f.method, f.methods_attempted, f.shape,
                       f.latitude, f.longitude, f.uncertainty_m, f.confidence, f.age_seconds,
                       f.accuracy_requested_m, f.accuracy_fulfilled, f.serving_cell, f.engine,
                       f.positioning_node, f.produced_at
                FROM tracked_subject t
                LEFT JOIN LATERAL (
                    SELECT r.request_id, lr.result_id, lr.method, lr.methods_attempted,
                           lr.shape, lr.latitude, lr.longitude, lr.uncertainty_m,
                           lr.confidence, lr.age_seconds, lr.accuracy_requested_m,
                           lr.accuracy_fulfilled, lr.serving_cell, lr.engine,
                           lr.positioning_node, lr.produced_at
                    FROM location_request r
                    JOIN location_result lr
                      ON lr.tenant_id = r.tenant_id AND lr.request_id = r.request_id
                    WHERE r.tenant_id = t.tenant_id AND r.client_id = t.client_id
                      AND r.purpose = t.purpose
                      AND ((t.subject_msisdn IS NOT NULL
                            AND r.subscriber_msisdn = t.subject_msisdn)
                        OR (t.subject_imsi IS NOT NULL
                            AND r.subscriber_imsi = t.subject_imsi))
                    ORDER BY lr.produced_at DESC, lr.result_id DESC
                    LIMIT 1
                ) f ON TRUE
                WHERE t.tenant_id = ? AND t.client_id = ? AND t.tracked_id = ?
                """,
                (rs, rowNum) -> new OwnedSubject(
                        rs.getObject("tracked_id", UUID.class),
                        rs.getObject("client_id", UUID.class),
                        rs.getString("subject_ref"),
                        rs.getString("purpose"),
                        rs.getBoolean("enabled"),
                        rs.getObject("request_id", UUID.class) == null
                                ? null : FIX_MAPPER.mapRow(rs, rowNum)),
                tenantId, clientId, trackedId).stream().findFirst();
    }

    public List<FixView> trail(long tenantId, UUID trackedId, int limit) {
        return jdbc.query(TRAIL, FIX_MAPPER, tenantId, trackedId, limit);
    }

    public boolean setEnabled(long tenantId, UUID trackedId, boolean enabled) {
        return jdbc.update("""
                UPDATE tracked_subject SET enabled = ?, updated_at = now()
                WHERE tenant_id = ? AND tracked_id = ?
                """, enabled, tenantId, trackedId) == 1;
    }

    public boolean delete(long tenantId, UUID trackedId) {
        return jdbc.update("DELETE FROM tracked_subject WHERE tenant_id = ? AND tracked_id = ?",
                tenantId, trackedId) == 1;
    }

    private static final RowMapper<FixView> FIX_MAPPER = (rs, rowNum) -> new FixView(
            rs.getObject("request_id", UUID.class),
            rs.getObject("result_id", UUID.class),
            rs.getString("method"),
            methods(rs.getArray("methods_attempted")),
            rs.getString("shape"),
            rs.getBigDecimal("latitude"),
            rs.getBigDecimal("longitude"),
            rs.getObject("uncertainty_m", Integer.class),
            rs.getObject("confidence", Integer.class),
            rs.getObject("age_seconds", Integer.class),
            rs.getObject("accuracy_requested_m", Integer.class),
            rs.getObject("accuracy_fulfilled", Boolean.class),
            rs.getString("serving_cell"),
            rs.getString("engine"),
            rs.getString("positioning_node"),
            rs.getTimestamp("produced_at") == null ? null
                    : rs.getTimestamp("produced_at").toInstant());

    private static final RowMapper<TrackedView> TRACKED_MAPPER = (rs, rowNum) -> new TrackedView(
            rs.getObject("tracked_id", UUID.class),
            rs.getObject("client_id", UUID.class),
            rs.getString("client_name"),
            rs.getString("subject_label"),
            rs.getString("purpose"),
            rs.getBoolean("enabled"),
            rs.getLong("fixes"),
            rs.getObject("request_id", UUID.class) == null ? null : FIX_MAPPER.mapRow(rs, rowNum),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());

    private static List<String> methods(Array array) throws SQLException {
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }

    public record TrackedView(UUID trackedId, UUID clientId, String clientName,
                              String subjectLabel, String purpose, boolean enabled, long fixes,
                              FixView lastFix, Instant createdAt, Instant updatedAt) {
    }

    public record OwnedSubject(UUID trackedId, UUID clientId, String subjectRef, String purpose,
                               boolean enabled, FixView lastFix) {
    }

    public record FixView(UUID requestId, UUID resultId, String method,
                          List<String> methodsAttempted, String shape, BigDecimal latitude,
                          BigDecimal longitude, Integer uncertaintyM, Integer confidence,
                          Integer ageSeconds, Integer accuracyRequestedM,
                          Boolean accuracyFulfilled, String servingCell, String engine,
                          String positioningNode, Instant producedAt) {
    }
}
