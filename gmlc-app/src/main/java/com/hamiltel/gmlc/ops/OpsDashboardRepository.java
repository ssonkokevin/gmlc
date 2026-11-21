package com.hamiltel.gmlc.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only queries behind the operations dashboard. Everything is computed in
 * the database from stored rows: the console never derives a figure from an
 * estimate, and a request or CDR that is missing simply does not appear. That is
 * what lets the console's numbers be checked against the same SQL.
 *
 * <p>Requests are the spine of the summary: a request with no CDR yet counts as
 * in flight, so the success rate's denominator is finished work only.
 */
@Repository
public class OpsDashboardRepository {

    private static final String BUCKET_ORIGIN = "TIMESTAMPTZ '2000-01-01 00:00:00+00'";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public OpsDashboardRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    // ------------------------------------------------------------------ totals

    public Totals totals(long tenantId, Instant from, Instant to) {
        List<Totals> rows = jdbc.query("""
                SELECT count(*) AS requests,
                       count(c.cdr_id) AS finished,
                       count(c.cdr_id) FILTER (WHERE c.record_status = 'ANSWERED') AS answered,
                       count(c.cdr_id) FILTER (WHERE c.record_status <> 'ANSWERED') AS failed,
                       avg(c.response_time_ms) FILTER (WHERE c.record_status = 'ANSWERED') AS avg_ms,
                       percentile_cont(0.5) WITHIN GROUP (ORDER BY c.response_time_ms)
                           FILTER (WHERE c.record_status = 'ANSWERED') AS p50_ms,
                       percentile_cont(0.95) WITHIN GROUP (ORDER BY c.response_time_ms)
                           FILTER (WHERE c.record_status = 'ANSWERED') AS p95_ms,
                       count(c.cdr_id) FILTER (WHERE c.accuracy_fulfilled) AS accuracy_ok,
                       count(c.accuracy_fulfilled) AS accuracy_measured
                FROM location_request r
                LEFT JOIN cdr c ON c.tenant_id = r.tenant_id AND c.request_id = r.request_id
                WHERE r.tenant_id = ? AND r.created_at >= ? AND r.created_at < ?
                """, (rs, n) -> new Totals(
                rs.getLong("requests"),
                rs.getLong("finished"),
                rs.getLong("answered"),
                rs.getLong("failed"),
                decimal(rs, "avg_ms"),
                decimal(rs, "p50_ms"),
                decimal(rs, "p95_ms"),
                rs.getLong("accuracy_ok"),
                rs.getLong("accuracy_measured")),
                tenantId, Timestamp.from(from), Timestamp.from(to));
        return rows.get(0);
    }

    public List<BucketPoint> series(long tenantId, OpsRange range) {
        return jdbc.query("""
                SELECT date_bin(?::interval, r.created_at, %s) AS bucket,
                       count(*) AS requests,
                       count(c.cdr_id) FILTER (WHERE c.record_status = 'ANSWERED') AS answered,
                       count(c.cdr_id) FILTER (WHERE c.record_status <> 'ANSWERED') AS failed,
                       avg(c.response_time_ms) FILTER (WHERE c.record_status = 'ANSWERED') AS avg_ms,
                       percentile_cont(0.95) WITHIN GROUP (ORDER BY c.response_time_ms)
                           FILTER (WHERE c.record_status = 'ANSWERED') AS p95_ms
                FROM location_request r
                LEFT JOIN cdr c ON c.tenant_id = r.tenant_id AND c.request_id = r.request_id
                WHERE r.tenant_id = ? AND r.created_at >= ? AND r.created_at < ?
                GROUP BY 1 ORDER BY 1
                """.formatted(BUCKET_ORIGIN), (rs, n) -> new BucketPoint(
                instant(rs, "bucket"),
                rs.getLong("requests"),
                rs.getLong("answered"),
                rs.getLong("failed"),
                decimal(rs, "avg_ms"),
                decimal(rs, "p95_ms")),
                range.bucketInterval(), tenantId, Timestamp.from(range.from()),
                Timestamp.from(range.to()));
    }

    // -------------------------------------------------------------- throughput

    public List<ProtocolPoint> throughput(long tenantId, OpsRange range) {
        return jdbc.query("""
                SELECT date_bin(?::interval, r.created_at, %s) AS bucket,
                       count(*) FILTER (WHERE r.protocol = 'MAP') AS map_requests,
                       count(*) FILTER (WHERE r.protocol = 'DIAMETER') AS diameter_requests,
                       count(*) FILTER (WHERE r.protocol IS NULL
                                          OR r.protocol NOT IN ('MAP', 'DIAMETER')) AS unattributed
                FROM location_request r
                WHERE r.tenant_id = ? AND r.created_at >= ? AND r.created_at < ?
                GROUP BY 1 ORDER BY 1
                """.formatted(BUCKET_ORIGIN), (rs, n) -> new ProtocolPoint(
                instant(rs, "bucket"),
                rs.getLong("map_requests"),
                rs.getLong("diameter_requests"),
                rs.getLong("unattributed")),
                range.bucketInterval(), tenantId, Timestamp.from(range.from()),
                Timestamp.from(range.to()));
    }

    // --------------------------------------------------------------------- mix

    public List<NameCount> byOperation(long tenantId, Instant from, Instant to) {
        return counts("coalesce(operation, 'UNATTRIBUTED')", "cdr", tenantId, from, to);
    }

    public List<NameCount> byMethod(long tenantId, Instant from, Instant to) {
        return counts("coalesce(positioning_method, 'UNATTRIBUTED')", "cdr", tenantId, from, to);
    }

    public List<NameCount> byRat(long tenantId, Instant from, Instant to) {
        return counts("coalesce(rat_type, 'UNATTRIBUTED')", "cdr", tenantId, from, to);
    }

    public List<NameCount> byResult(long tenantId, Instant from, Instant to) {
        return counts("coalesce(result_code, 'UNATTRIBUTED')", "cdr", tenantId, from, to);
    }

    private List<NameCount> counts(String expression, String table, long tenantId,
                                   Instant from, Instant to) {
        return jdbc.query("""
                SELECT %s AS name, count(*) AS n
                FROM %s
                WHERE tenant_id = ? AND created_at >= ? AND created_at < ?
                GROUP BY 1 ORDER BY n DESC, name
                """.formatted(expression, table), (rs, n) -> new NameCount(
                rs.getString("name"), rs.getLong("n")),
                tenantId, Timestamp.from(from), Timestamp.from(to));
    }

    // ---------------------------------------------------------------- requests

    public long requestCount(long tenantId, Instant from, Instant to, RequestFilter filter) {
        List<Object> args = new ArrayList<>();
        String where = requestWhere(tenantId, from, to, filter, args);
        Long count = jdbc.queryForObject("SELECT count(*) FROM location_request r"
                + " LEFT JOIN cdr c ON c.tenant_id = r.tenant_id AND c.request_id = r.request_id"
                + where, Long.class, args.toArray());
        return count == null ? 0 : count;
    }

    public List<RequestRow> requests(long tenantId, OpsRange range, RequestFilter filter,
                                     int limit, int offset) {
        List<Object> args = new ArrayList<>();
        String where = requestWhere(tenantId, range.from(), range.to(), filter, args);
        args.add(limit);
        args.add(offset);
        return jdbc.query("""
                SELECT r.request_id, r.created_at, r.completed_at, r.client_id,
                       a.name AS client_name, r.purpose, r.subscriber_msisdn,
                       r.subscriber_imsi, r.state, r.error_code, r.protocol,
                       c.operation, c.record_status, c.result_code, c.positioning_method,
                       c.rat_type, c.uncertainty_m, c.confidence, c.age_of_location_s,
                       c.response_time_ms, c.accuracy_fulfilled, c.accuracy_requested_m,
                       c.latitude, c.longitude, c.shape, c.serving_node
                FROM location_request r
                LEFT JOIN cdr c ON c.tenant_id = r.tenant_id AND c.request_id = r.request_id
                LEFT JOIN api_client a ON a.client_id = r.client_id
                """ + where + " ORDER BY r.created_at DESC, r.request_id LIMIT ? OFFSET ?",
                REQUEST_MAPPER, args.toArray());
    }

    private String requestWhere(long tenantId, Instant from, Instant to, RequestFilter filter,
                                List<Object> args) {
        StringBuilder where = new StringBuilder(
                " WHERE r.tenant_id = ? AND r.created_at >= ? AND r.created_at < ?");
        args.add(tenantId);
        args.add(Timestamp.from(from));
        args.add(Timestamp.from(to));
        if (StringUtils.hasText(filter.state())) {
            where.append(" AND r.state = ?");
            args.add(filter.state());
        }
        if (StringUtils.hasText(filter.protocol())) {
            where.append(" AND r.protocol = ?");
            args.add(filter.protocol());
        }
        if (StringUtils.hasText(filter.purpose())) {
            where.append(" AND r.purpose = ?");
            args.add(filter.purpose());
        }
        if (filter.clientId() != null) {
            where.append(" AND r.client_id = ?");
            args.add(filter.clientId());
        }
        if (StringUtils.hasText(filter.recordStatus())) {
            where.append(" AND c.record_status = ?");
            args.add(filter.recordStatus());
        }
        return where.toString();
    }

    private static final RowMapper<RequestRow> REQUEST_MAPPER = (rs, n) -> new RequestRow(
            rs.getObject("request_id", UUID.class),
            instant(rs, "created_at"),
            instant(rs, "completed_at"),
            rs.getObject("client_id", UUID.class),
            rs.getString("client_name"),
            rs.getString("purpose"),
            rs.getString("subscriber_msisdn"),
            rs.getString("subscriber_imsi"),
            rs.getString("state"),
            rs.getString("error_code"),
            rs.getString("protocol"),
            rs.getString("operation"),
            rs.getString("record_status"),
            rs.getString("result_code"),
            rs.getString("positioning_method"),
            rs.getString("rat_type"),
            rs.getObject("uncertainty_m", Integer.class),
            shortOrNull(rs, "confidence"),
            rs.getObject("age_of_location_s", Integer.class),
            rs.getObject("response_time_ms", Integer.class),
            rs.getObject("accuracy_fulfilled", Boolean.class),
            rs.getObject("accuracy_requested_m", Integer.class),
            rs.getBigDecimal("latitude"),
            rs.getBigDecimal("longitude"),
            rs.getString("shape"),
            rs.getString("serving_node"));

    // ------------------------------------------------------------------ detail

    public Optional<RequestDetail> request(long tenantId, UUID requestId) {
        List<RequestDetail> rows = jdbc.query("""
                SELECT r.request_id, r.tenant_id, r.client_id, a.name AS client_name,
                       r.idempotency_key, r.purpose, r.subscriber_msisdn, r.subscriber_imsi,
                       r.requested_accuracy_m, r.requested_priority, r.state, r.error_code,
                       r.error_detail, r.protocol, r.rat, r.serving_node, r.positioning_node,
                       r.created_at, r.updated_at, r.deadline_at, r.completed_at, r.result_id
                FROM location_request r
                LEFT JOIN api_client a ON a.client_id = r.client_id
                WHERE r.tenant_id = ? AND r.request_id = ?
                """, (rs, n) -> new RequestDetail(
                rs.getObject("request_id", UUID.class),
                rs.getLong("tenant_id"),
                rs.getObject("client_id", UUID.class),
                rs.getString("client_name"),
                rs.getString("idempotency_key"),
                rs.getString("purpose"),
                rs.getString("subscriber_msisdn"),
                rs.getString("subscriber_imsi"),
                rs.getObject("requested_accuracy_m", Integer.class),
                rs.getString("requested_priority"),
                rs.getString("state"),
                rs.getString("error_code"),
                rs.getString("error_detail"),
                rs.getString("protocol"),
                rs.getString("rat"),
                rs.getString("serving_node"),
                rs.getString("positioning_node"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"),
                instant(rs, "deadline_at"),
                instant(rs, "completed_at"),
                rs.getObject("result_id", UUID.class)),
                tenantId, requestId);
        return rows.stream().findFirst();
    }

    public Optional<CdrDetail> cdr(long tenantId, UUID requestId) {
        List<CdrDetail> rows = jdbc.query("""
                SELECT cdr_id, result_code, record_status, protocol, operation,
                       methods_attempted, positioning_method, rat_type,
                       response_time_ms, accuracy_requested_m, accuracy_achieved_m,
                       accuracy_fulfilled, age_of_location_s, paging_performed, serving_node,
                       positioning_node, uncertainty_m, confidence, shape, latitude, longitude,
                       created_at
                FROM cdr
                WHERE tenant_id = ? AND request_id = ?
                ORDER BY cdr_id DESC LIMIT 1
                """, (rs, n) -> {
            Array methods = rs.getArray("methods_attempted");
            return new CdrDetail(
                    rs.getLong("cdr_id"),
                    rs.getString("result_code"),
                    rs.getString("record_status"),
                    rs.getString("protocol"),
                    rs.getString("operation"),
                    methods == null ? List.of()
                            : Arrays.asList((String[]) methods.getArray()),
                    rs.getString("positioning_method"),
                    rs.getString("rat_type"),
                    rs.getObject("response_time_ms", Integer.class),
                    rs.getObject("accuracy_requested_m", Integer.class),
                    rs.getObject("accuracy_achieved_m", Integer.class),
                    rs.getObject("accuracy_fulfilled", Boolean.class),
                    rs.getObject("age_of_location_s", Integer.class),
                    rs.getBoolean("paging_performed"),
                    rs.getString("serving_node"),
                    rs.getString("positioning_node"),
                    rs.getObject("uncertainty_m", Integer.class),
                    shortOrNull(rs, "confidence"),
                    rs.getString("shape"),
                    rs.getBigDecimal("latitude"),
                    rs.getBigDecimal("longitude"),
                    instant(rs, "created_at"));
        }, tenantId, requestId);
        return rows.stream().findFirst();
    }

    /**
     * What the gateway itself recorded for this request: the audit rows and the
     * CDR write timestamp, plus the request's own creation time. Nothing is
     * synthesised; a step the gateway did not record is absent.
     */
    public List<TimelineEvent> timeline(long tenantId, UUID requestId, Instant createdAt,
                                        CdrDetail cdr) {
        List<TimelineEvent> events = new ArrayList<>();
        events.add(new TimelineEvent(createdAt, "RECEIVED", null, "REQUEST", null));

        jdbc.query("""
                SELECT occurred_at, action, outcome, detail::text AS detail
                FROM audit_event
                WHERE tenant_id = ? AND request_id = ?
                ORDER BY occurred_at, audit_id
                """, rs -> {
            events.add(new TimelineEvent(
                    instant(rs, "occurred_at"),
                    rs.getString("action"),
                    rs.getString("outcome"),
                    "AUDIT",
                    json(rs.getString("detail"))));
        }, tenantId, requestId);

        if (cdr != null) {
            events.add(new TimelineEvent(cdr.createdAt(), "CDR_" + cdr.recordStatus(),
                    cdr.recordStatus(), "CDR", null));
        }
        events.sort((a, b) -> a.at().compareTo(b.at()));
        return events;
    }

    /** Requests still open, and how many of those are past their deadline. */
    public InFlight inFlight(long tenantId) {
        List<InFlight> rows = jdbc.query("""
                SELECT count(*) AS open,
                       count(*) FILTER (WHERE deadline_at < now()) AS past_deadline
                FROM location_request
                WHERE tenant_id = ?
                  AND state NOT IN ('DELIVERED', 'FAILED', 'TIMEOUT')
                """, (rs, n) -> new InFlight(rs.getLong("open"), rs.getLong("past_deadline")),
                tenantId);
        return rows.get(0);
    }

    /**
     * Failures whose cause is the cell plan rather than the subscriber or the
     * network: a cell the plan does not hold, a cell without a radius, or a
     * cell code that matches more than one plan row.
     */
    public long cellGapFailures(long tenantId, Instant from, Instant to) {
        Long count = jdbc.queryForObject("""
                SELECT count(*) FROM cdr
                WHERE tenant_id = ? AND created_at >= ? AND created_at < ?
                  AND record_status IN ('FAILED', 'ABORTED', 'TIMEOUT')
                  AND result_code IN ('CELL_NOT_IN_PLAN', 'CELL_WITHOUT_RADIUS', 'AMBIGUOUS_CELL')
                """, Long.class, tenantId, Timestamp.from(from), Timestamp.from(to));
        return count == null ? 0 : count;
    }

    // ----------------------------------------------------------------- records

    public record Totals(
            long requests,
            long finished,
            long answered,
            long failed,
            Double averageResponseMs,
            Double p50ResponseMs,
            Double p95ResponseMs,
            long accuracyFulfilled,
            long accuracyMeasured) {

        public long inFlight() {
            return requests - finished;
        }

        public Double successRate() {
            return finished == 0 ? null : (double) answered / finished;
        }

        public Double accuracyFulfilledRate() {
            return accuracyMeasured == 0 ? null : (double) accuracyFulfilled / accuracyMeasured;
        }
    }

    public record BucketPoint(
            Instant bucket,
            long requests,
            long answered,
            long failed,
            Double averageResponseMs,
            Double p95ResponseMs) {
    }

    public record ProtocolPoint(
            Instant bucket,
            long mapRequests,
            long diameterRequests,
            long unattributed) {

        public long total() {
            return mapRequests + diameterRequests + unattributed;
        }
    }

    public record NameCount(String name, long count) {
    }

    public record RequestFilter(String state, String protocol, String purpose, UUID clientId,
                                String recordStatus) {
    }

    public record RequestRow(
            UUID requestId,
            Instant createdAt,
            Instant completedAt,
            UUID clientId,
            String clientName,
            String purpose,
            String msisdn,
            String imsi,
            String state,
            String errorCode,
            String protocol,
            String operation,
            String recordStatus,
            String resultCode,
            String method,
            String rat,
            Integer uncertaintyM,
            Integer confidence,
            Integer ageSeconds,
            Integer responseTimeMs,
            Boolean accuracyFulfilled,
            Integer accuracyRequestedM,
            BigDecimal latitude,
            BigDecimal longitude,
            String shape,
            String servingNode) {
    }

    public record RequestDetail(
            UUID requestId,
            long tenantId,
            UUID clientId,
            String clientName,
            String idempotencyKey,
            String purpose,
            String msisdn,
            String imsi,
            Integer accuracyRequestedM,
            String priority,
            String state,
            String errorCode,
            String errorDetail,
            String protocol,
            String rat,
            String servingNode,
            String positioningNode,
            Instant createdAt,
            Instant updatedAt,
            Instant deadlineAt,
            Instant completedAt,
            UUID resultId) {
    }

    public record CdrDetail(
            long cdrId,
            String resultCode,
            String recordStatus,
            String protocol,
            String operation,
            List<String> methodsAttempted,
            String method,
            String rat,
            Integer responseTimeMs,
            Integer accuracyRequestedM,
            Integer accuracyAchievedM,
            Boolean accuracyFulfilled,
            Integer ageSeconds,
            boolean pagingPerformed,
            String servingNode,
            String positioningNode,
            Integer uncertaintyM,
            Integer confidence,
            String shape,
            BigDecimal latitude,
            BigDecimal longitude,
            Instant createdAt) {
    }

    public record TimelineEvent(Instant at, String action, String outcome, String source,
                                JsonNode detail) {
    }

    public record InFlight(long open, long pastDeadline) {
    }

    // ----------------------------------------------------------------- helpers

    private JsonNode json(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return mapper.readTree(value);
        } catch (Exception e) {
            return null;
        }
    }

    private static Double decimal(ResultSet rs, String column) throws SQLException {
        BigDecimal value = rs.getBigDecimal(column);
        return value == null ? null : value.doubleValue();
    }

    private static Integer shortOrNull(ResultSet rs, String column) throws SQLException {
        Short value = rs.getObject(column, Short.class);
        return value == null ? null : value.intValue();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
