package com.hamiltel.gmlc.cdr;

import com.hamiltel.gmlc.location.LocationEstimate;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * One row per finished request. The subscriber is recorded only as the
 * pseudonymous reference; the raw MSISDN/IMSI stay in the request row.
 */
@Repository
public class CdrRepository {

    private final JdbcTemplate jdbc;

    public CdrRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void writeFailure(long tenantId, UUID requestId, UUID clientId, UUID consentId,
                             String subscriberRef, String resultCode, Integer accuracyRequestedM,
                             String protocol, long responseTimeMs) {
        jdbc.update("""
                INSERT INTO cdr (tenant_id, request_id, client_id, consent_id, subscriber_ref,
                    result_code, accuracy_requested_m, protocol, response_time_ms, record_status,
                    methods_attempted)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'FAILED', ARRAY[]::text[])
                """,
                tenantId, requestId, clientId, consentId, subscriberRef, resultCode,
                accuracyRequestedM, protocol, (int) Math.max(0, responseTimeMs));
    }

    /**
     * One row for an answered request, with the envelope duplicated so a CDR
     * export can be read without joining the result table.
     */
    public void writeSuccess(long tenantId, UUID requestId, UUID clientId, UUID consentId,
                             String subscriberRef, LocationEstimate estimate, String protocol,
                             String operation, long responseTimeMs) {
        jdbc.update("""
                INSERT INTO cdr (tenant_id, request_id, client_id, consent_id, subscriber_ref,
                    rat_type, positioning_method, methods_attempted, result_code,
                    accuracy_requested_m, accuracy_achieved_m, accuracy_fulfilled,
                    response_time_ms, positioning_node, serving_node, age_of_location_s,
                    shape, latitude, longitude, uncertainty_m, confidence, record_status,
                    protocol, operation)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'OK', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        'ANSWERED', ?, ?)
                """,
                tenantId, requestId, clientId, consentId, subscriberRef,
                estimate.rat(), estimate.method(),
                estimate.methodsAttempted().toArray(new String[0]),
                estimate.accuracyRequestedM(), estimate.uncertaintyM(), estimate.accuracyFulfilled(),
                (int) Math.max(0, responseTimeMs), estimate.positioningNode(),
                estimate.servingCell(), estimate.ageSeconds(), estimate.shape(),
                estimate.latitude(), estimate.longitude(), estimate.uncertaintyM(),
                estimate.confidence(), protocol, operation);
    }

    public List<CdrRow> recent(long tenantId, UUID clientId, int limit) {
        return jdbc.query("""
                SELECT cdr_id, request_id, result_code, record_status, rat_type,
                       positioning_method, methods_attempted, accuracy_requested_m,
                       accuracy_achieved_m, accuracy_fulfilled, response_time_ms,
                       age_of_location_s, shape, latitude, longitude, uncertainty_m,
                       confidence, created_at
                FROM cdr
                WHERE tenant_id = ? AND client_id = ?
                ORDER BY cdr_id DESC
                LIMIT ?
                """, MAPPER, tenantId, clientId, limit);
    }

    private static final RowMapper<CdrRow> MAPPER = (rs, rowNum) -> {
        java.sql.Array methods = rs.getArray("methods_attempted");
        Timestamp createdAt = rs.getTimestamp("created_at");
        return new CdrRow(
                rs.getLong("cdr_id"),
                rs.getObject("request_id", UUID.class),
                rs.getString("result_code"),
                rs.getString("record_status"),
                rs.getString("rat_type"),
                rs.getString("positioning_method"),
                methods == null ? List.of() : Arrays.asList((String[]) methods.getArray()),
                rs.getObject("accuracy_requested_m", Integer.class),
                rs.getObject("accuracy_achieved_m", Integer.class),
                rs.getObject("accuracy_fulfilled", Boolean.class),
                rs.getObject("response_time_ms", Integer.class),
                rs.getObject("age_of_location_s", Integer.class),
                rs.getString("shape"),
                rs.getBigDecimal("latitude"),
                rs.getBigDecimal("longitude"),
                rs.getObject("uncertainty_m", Integer.class),
                rs.getObject("confidence", Short.class),
                createdAt == null ? null : createdAt.toInstant());
    };

    /**
     * A completed attempt, with the envelope fields next to the coordinates so
     * a reader can never mistake the position for a precise one.
     */
    public record CdrRow(
            long cdrId,
            UUID requestId,
            String resultCode,
            String recordStatus,
            String ratType,
            String positioningMethod,
            List<String> methodsAttempted,
            Integer accuracyRequestedM,
            Integer accuracyAchievedM,
            Boolean accuracyFulfilled,
            Integer responseTimeMs,
            Integer ageOfLocationS,
            String shape,
            BigDecimal latitude,
            BigDecimal longitude,
            Integer uncertaintyM,
            Short confidence,
            Instant createdAt) {
    }
}
