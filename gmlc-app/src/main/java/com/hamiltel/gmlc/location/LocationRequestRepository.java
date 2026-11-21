package com.hamiltel.gmlc.location;

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
 * Request rows. State moves are conditional updates on the expected current
 * state, so two workers cannot both advance the same request, and a late
 * answer cannot overwrite a terminal state.
 */
@Repository
public class LocationRequestRepository {

    private static final String COLUMNS = """
            request_id, tenant_id, client_id, idempotency_key, purpose,
            subscriber_msisdn, subscriber_imsi, requested_accuracy_m, requested_priority,
            state, error_code, error_detail, result_id,
            created_at, updated_at, deadline_at, completed_at
            """;

    private final JdbcTemplate jdbc;

    public LocationRequestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return false when the idempotency key already belongs to a request */
    public boolean insert(UUID requestId, long tenantId, UUID clientId, String idempotencyKey,
                          String purpose, String msisdn, String imsi, Integer accuracyMeters,
                          String priority, String protocol, Instant deadline) {
        int rows = jdbc.update("""
                INSERT INTO location_request (request_id, tenant_id, client_id, idempotency_key,
                    purpose, subscriber_msisdn, subscriber_imsi, requested_accuracy_m,
                    requested_priority, state, protocol, deadline_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'RECEIVED', ?, ?)
                ON CONFLICT DO NOTHING
                """,
                requestId, tenantId, clientId, idempotencyKey, purpose, msisdn, imsi,
                accuracyMeters, priority, protocol, Timestamp.from(deadline));
        return rows == 1;
    }

    public Optional<StoredRequest> findByKey(long tenantId, UUID clientId, String idempotencyKey) {
        List<StoredRequest> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM location_request"
                        + " WHERE tenant_id = ? AND client_id = ? AND idempotency_key = ?",
                MAPPER, tenantId, clientId, idempotencyKey);
        return rows.stream().findFirst();
    }

    public Optional<StoredRequest> find(long tenantId, UUID clientId, UUID requestId) {
        List<StoredRequest> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM location_request"
                        + " WHERE tenant_id = ? AND client_id = ? AND request_id = ?",
                MAPPER, tenantId, clientId, requestId);
        return rows.stream().findFirst();
    }

    /**
     * Moves the row one edge. The caller has already checked the edge is legal
     * in {@link RequestState}; this method checks that the row is still where
     * the caller thought it was.
     */
    public void advance(long tenantId, UUID requestId, RequestState from, RequestState to,
                        String errorCode, String errorDetail) {
        if (!from.canMoveTo(to)) {
            throw new IllegalArgumentException("illegal state transition " + from + " -> " + to);
        }
        int rows = jdbc.update("""
                UPDATE location_request
                SET state = ?, error_code = ?, error_detail = ?, updated_at = now(),
                    completed_at = CASE WHEN ? THEN now() ELSE completed_at END
                WHERE tenant_id = ? AND request_id = ? AND state = ?
                """,
                to.name(), errorCode, errorDetail, to.isFinal(), tenantId, requestId, from.name());
        if (rows != 1) {
            throw new IllegalStateException("request " + requestId + " is not in state " + from);
        }
    }

    public void attachResult(long tenantId, UUID requestId, UUID resultId) {
        int rows = jdbc.update(
                "UPDATE location_request SET result_id = ?, updated_at = now()"
                        + " WHERE tenant_id = ? AND request_id = ?",
                resultId, tenantId, requestId);
        if (rows != 1) {
            throw new IllegalStateException("request " + requestId + " vanished before its result");
        }
    }

    private static final RowMapper<StoredRequest> MAPPER = (rs, rowNum) -> new StoredRequest(
            rs.getObject("request_id", UUID.class),
            rs.getLong("tenant_id"),
            rs.getObject("client_id", UUID.class),
            rs.getString("idempotency_key"),
            rs.getString("purpose"),
            rs.getString("subscriber_msisdn"),
            rs.getString("subscriber_imsi"),
            (Integer) rs.getObject("requested_accuracy_m"),
            rs.getString("requested_priority"),
            rs.getString("state"),
            rs.getString("error_code"),
            rs.getString("error_detail"),
            rs.getObject("result_id", UUID.class),
            instant(rs, "created_at"),
            instant(rs, "updated_at"),
            instant(rs, "deadline_at"),
            instant(rs, "completed_at"));

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    public record StoredRequest(
            UUID requestId,
            long tenantId,
            UUID clientId,
            String idempotencyKey,
            String purpose,
            String msisdn,
            String imsi,
            Integer accuracyRequestedM,
            String priority,
            String state,
            String errorCode,
            String errorDetail,
            UUID resultId,
            Instant createdAt,
            Instant updatedAt,
            Instant deadlineAt,
            Instant completedAt) {

        public RequestState stateValue() {
            return RequestState.valueOf(state);
        }
    }
}
