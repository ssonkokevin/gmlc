package com.hamiltel.gmlc.location;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Results are written once and never updated: a client that sees a position
 * can rely on it meaning the same thing later. The envelope columns are all
 * NOT NULL in the schema, so no row can exist without its uncertainty,
 * confidence and age.
 */
@Repository
public class LocationResultRepository {

    private final JdbcTemplate jdbc;

    public LocationResultRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long tenantId, UUID requestId, UUID resultId, LocationEstimate estimate) {
        jdbc.update("""
                INSERT INTO location_result (result_id, tenant_id, request_id, method,
                    methods_attempted, shape, latitude, longitude, uncertainty_m, confidence,
                    age_seconds, accuracy_requested_m, accuracy_fulfilled, serving_cell,
                    geometry_data_quality, engine, positioning_node, produced_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                resultId, tenantId, requestId, estimate.method(),
                estimate.methodsAttempted().toArray(new String[0]), estimate.shape(),
                estimate.latitude(), estimate.longitude(), estimate.uncertaintyM(),
                estimate.confidence(), estimate.ageSeconds(), estimate.accuracyRequestedM(),
                estimate.accuracyFulfilled(), estimate.servingCell(), estimate.geometryDataQuality(),
                estimate.engine(), estimate.positioningNode(), Timestamp.from(estimate.producedAt()));
    }

    public Optional<StoredResult> find(long tenantId, UUID resultId) {
        List<StoredResult> rows = jdbc.query("""
                SELECT result_id, request_id, method, methods_attempted, shape, latitude,
                       longitude, uncertainty_m, confidence, age_seconds, accuracy_requested_m,
                       accuracy_fulfilled, serving_cell, engine, positioning_node, produced_at
                FROM location_result
                WHERE tenant_id = ? AND result_id = ?
                """, MAPPER, tenantId, resultId);
        return rows.stream().findFirst();
    }

    private static final RowMapper<StoredResult> MAPPER = (rs, rowNum) -> {
        Array methods = rs.getArray("methods_attempted");
        return new StoredResult(
                rs.getObject("result_id", UUID.class),
                rs.getObject("request_id", UUID.class),
                rs.getString("method"),
                methods == null ? List.of() : Arrays.asList((String[]) methods.getArray()),
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
    };

    public record StoredResult(
            UUID resultId,
            UUID requestId,
            String method,
            List<String> methodsAttempted,
            String shape,
            BigDecimal latitude,
            BigDecimal longitude,
            Integer uncertaintyM,
            Integer confidence,
            Integer ageSeconds,
            Integer accuracyRequestedM,
            Boolean accuracyFulfilled,
            String servingCell,
            String engine,
            String positioningNode,
            Instant producedAt) {
    }
}
