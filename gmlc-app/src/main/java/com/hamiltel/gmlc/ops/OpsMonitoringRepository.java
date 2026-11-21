package com.hamiltel.gmlc.ops;

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
import java.util.UUID;

/**
 * Read-only queries behind Monitoring &amp; Tracing and Reports. The exchange
 * list is the stored CDR, which is what the gateway actually wrote after each
 * signalling attempt; the series and report aggregates are computed in the
 * database from those same rows, using the same definitions as the dashboard
 * (answered means {@code cdr.record_status = 'ANSWERED'}).
 *
 * <p>Reports use the timestamp of the row they group: request-based sections use
 * {@code location_request.created_at}, signalling sections use
 * {@code cdr.created_at}, and the cell section uses
 * {@code location_result.created_at}. The console shows that basis so a reader
 * can check each figure against the same SQL.
 */
@Repository
public class OpsMonitoringRepository {

    private static final String BUCKET_ORIGIN = "TIMESTAMPTZ '2000-01-01 00:00:00+00'";

    private final JdbcTemplate jdbc;

    public OpsMonitoringRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // -------------------------------------------------------------- exchanges

    public long exchangeCount(long tenantId, Instant from, Instant to, ExchangeFilter filter) {
        List<Object> args = new ArrayList<>();
        String where = exchangeWhere(tenantId, from, to, filter, args);
        Long count = jdbc.queryForObject("SELECT count(*) FROM cdr c" + where, Long.class,
                args.toArray());
        return count == null ? 0 : count;
    }

    public List<ExchangeRow> exchanges(long tenantId, Instant from, Instant to,
                                       ExchangeFilter filter, int limit, int offset) {
        List<Object> args = new ArrayList<>();
        String where = exchangeWhere(tenantId, from, to, filter, args);
        args.add(limit);
        args.add(offset);
        return jdbc.query("""
                SELECT c.cdr_id, c.created_at, c.request_id, c.client_id, a.name AS client_name,
                       c.protocol, c.operation, c.record_status, c.result_code, c.rat_type,
                       c.positioning_method, c.methods_attempted, c.response_time_ms,
                       c.paging_performed, c.serving_node, c.positioning_node,
                       c.uncertainty_m, c.confidence, c.age_of_location_s,
                       c.accuracy_requested_m, c.accuracy_achieved_m, c.accuracy_fulfilled,
                       c.shape, c.latitude, c.longitude
                FROM cdr c
                LEFT JOIN api_client a ON a.client_id = c.client_id
                """ + where + " ORDER BY c.created_at DESC, c.cdr_id DESC LIMIT ? OFFSET ?",
                EXCHANGE_MAPPER, args.toArray());
    }

    public List<ExchangeBucket> exchangeSeries(long tenantId, OpsRange range, String protocol) {
        boolean filtered = StringUtils.hasText(protocol);
        List<Object> args = new ArrayList<>();
        args.add(range.bucketInterval());
        args.add(tenantId);
        args.add(Timestamp.from(range.from()));
        args.add(Timestamp.from(range.to()));
        if (filtered) {
            args.add(protocol);
        }
        return jdbc.query("""
                SELECT date_bin(?::interval, c.created_at, %s) AS bucket,
                       count(*) FILTER (WHERE c.record_status = 'ANSWERED') AS answered,
                       count(*) FILTER (WHERE c.record_status <> 'ANSWERED') AS failed,
                       avg(c.response_time_ms) FILTER (WHERE c.record_status = 'ANSWERED') AS avg_ms,
                       percentile_cont(0.95) WITHIN GROUP (ORDER BY c.response_time_ms)
                           FILTER (WHERE c.record_status = 'ANSWERED') AS p95_ms
                FROM cdr c
                WHERE c.tenant_id = ? AND c.created_at >= ? AND c.created_at < ?%s
                GROUP BY 1 ORDER BY 1
                """.formatted(BUCKET_ORIGIN, filtered ? " AND c.protocol = ?" : ""),
                (rs, n) -> new ExchangeBucket(
                        instant(rs, "bucket"),
                        rs.getLong("answered"),
                        rs.getLong("failed"),
                        decimal(rs, "avg_ms"),
                        decimal(rs, "p95_ms")),
                args.toArray());
    }

    public ExchangeTotals exchangeTotals(long tenantId, OpsRange range, String protocol) {
        List<Object> args = new ArrayList<>();
        String where = exchangeWhere(tenantId, range.from(), range.to(),
                new ExchangeFilter(protocol, null, null), args);
        List<ExchangeTotals> rows = jdbc.query("""
                SELECT count(*) AS finished,
                       count(*) FILTER (WHERE c.record_status = 'ANSWERED') AS answered,
                       count(*) FILTER (WHERE c.record_status <> 'ANSWERED') AS failed,
                       avg(c.response_time_ms) FILTER (WHERE c.record_status = 'ANSWERED') AS avg_ms,
                       percentile_cont(0.5) WITHIN GROUP (ORDER BY c.response_time_ms)
                           FILTER (WHERE c.record_status = 'ANSWERED') AS p50_ms,
                       percentile_cont(0.95) WITHIN GROUP (ORDER BY c.response_time_ms)
                           FILTER (WHERE c.record_status = 'ANSWERED') AS p95_ms
                FROM cdr c
                """ + where, (rs, n) -> new ExchangeTotals(
                rs.getLong("finished"),
                rs.getLong("answered"),
                rs.getLong("failed"),
                decimal(rs, "avg_ms"),
                decimal(rs, "p50_ms"),
                decimal(rs, "p95_ms")),
                args.toArray());
        return rows.get(0);
    }

    public List<NameCount> exchangeProtocols(long tenantId, OpsRange range) {
        return jdbc.query("""
                SELECT coalesce(c.protocol, 'UNATTRIBUTED') AS name, count(*) AS n
                FROM cdr c
                WHERE c.tenant_id = ? AND c.created_at >= ? AND c.created_at < ?
                GROUP BY 1 ORDER BY n DESC, name
                """, (rs, n) -> new NameCount(rs.getString("name"), rs.getLong("n")),
                tenantId, Timestamp.from(range.from()), Timestamp.from(range.to()));
    }

    private String exchangeWhere(long tenantId, Instant from, Instant to, ExchangeFilter filter,
                                 List<Object> args) {
        StringBuilder where = new StringBuilder(
                " WHERE c.tenant_id = ? AND c.created_at >= ? AND c.created_at < ?");
        args.add(tenantId);
        args.add(Timestamp.from(from));
        args.add(Timestamp.from(to));
        if (StringUtils.hasText(filter.protocol())) {
            where.append(" AND c.protocol = ?");
            args.add(filter.protocol());
        }
        if (StringUtils.hasText(filter.recordStatus())) {
            where.append(" AND c.record_status = ?");
            args.add(filter.recordStatus());
        }
        if (filter.requestId() != null) {
            where.append(" AND c.request_id = ?");
            args.add(filter.requestId());
        }
        return where.toString();
    }

    private static final RowMapper<ExchangeRow> EXCHANGE_MAPPER = (rs, n) -> {
        Array methods = rs.getArray("methods_attempted");
        return new ExchangeRow(
                rs.getLong("cdr_id"),
                instant(rs, "created_at"),
                rs.getObject("request_id", UUID.class),
                rs.getObject("client_id", UUID.class),
                rs.getString("client_name"),
                rs.getString("protocol"),
                rs.getString("operation"),
                rs.getString("record_status"),
                rs.getString("result_code"),
                rs.getString("rat_type"),
                rs.getString("positioning_method"),
                methods == null ? List.of() : Arrays.asList((String[]) methods.getArray()),
                rs.getObject("response_time_ms", Integer.class),
                rs.getBoolean("paging_performed"),
                rs.getString("serving_node"),
                rs.getString("positioning_node"),
                rs.getObject("uncertainty_m", Integer.class),
                shortOrNull(rs, "confidence"),
                rs.getObject("age_of_location_s", Integer.class),
                rs.getObject("accuracy_requested_m", Integer.class),
                rs.getObject("accuracy_achieved_m", Integer.class),
                rs.getObject("accuracy_fulfilled", Boolean.class),
                rs.getString("shape"),
                rs.getBigDecimal("latitude"),
                rs.getBigDecimal("longitude"));
    };

    // ---------------------------------------------------------------- reports

    public List<ClientReport> byClient(long tenantId, Instant from, Instant to) {
        return clientBreakdown("coalesce(a.name, 'UNKNOWN')", tenantId, from, to);
    }

    public List<ClientReport> byPurpose(long tenantId, Instant from, Instant to) {
        return clientBreakdown("coalesce(r.purpose, 'UNATTRIBUTED')", tenantId, from, to);
    }

    private List<ClientReport> clientBreakdown(String nameExpression, long tenantId,
                                               Instant from, Instant to) {
        return jdbc.query("""
                SELECT %s AS name,
                       count(*) AS requests,
                       count(c.cdr_id) AS finished,
                       count(c.cdr_id) FILTER (WHERE c.record_status = 'ANSWERED') AS answered,
                       count(c.cdr_id) FILTER (WHERE c.record_status <> 'ANSWERED') AS failed,
                       avg(c.response_time_ms) FILTER (WHERE c.record_status = 'ANSWERED') AS avg_ms,
                       percentile_cont(0.95) WITHIN GROUP (ORDER BY c.response_time_ms)
                           FILTER (WHERE c.record_status = 'ANSWERED') AS p95_ms
                FROM location_request r
                LEFT JOIN cdr c ON c.tenant_id = r.tenant_id AND c.request_id = r.request_id
                LEFT JOIN api_client a ON a.client_id = r.client_id
                WHERE r.tenant_id = ? AND r.created_at >= ? AND r.created_at < ?
                GROUP BY 1 ORDER BY requests DESC, name LIMIT 100
                """.formatted(nameExpression), (rs, n) -> {
            long requests = rs.getLong("requests");
            long finished = rs.getLong("finished");
            long answered = rs.getLong("answered");
            return new ClientReport(rs.getString("name"), requests, finished, answered,
                    rs.getLong("failed"), requests - finished,
                    finished == 0 ? null : (double) answered / finished,
                    decimal(rs, "avg_ms"), decimal(rs, "p95_ms"));
        }, tenantId, Timestamp.from(from), Timestamp.from(to));
    }

    public List<RatReport> byRat(long tenantId, Instant from, Instant to) {
        return jdbc.query("""
                SELECT coalesce(c.rat_type, 'UNATTRIBUTED') AS name,
                       count(*) AS total,
                       count(*) FILTER (WHERE c.record_status = 'ANSWERED') AS answered,
                       count(*) FILTER (WHERE c.record_status <> 'ANSWERED') AS failed,
                       avg(c.response_time_ms) FILTER (WHERE c.record_status = 'ANSWERED') AS avg_ms,
                       percentile_cont(0.95) WITHIN GROUP (ORDER BY c.response_time_ms)
                           FILTER (WHERE c.record_status = 'ANSWERED') AS p95_ms
                FROM cdr c
                WHERE c.tenant_id = ? AND c.created_at >= ? AND c.created_at < ?
                GROUP BY 1 ORDER BY total DESC, name LIMIT 50
                """, (rs, n) -> new RatReport(rs.getString("name"), rs.getLong("answered"),
                rs.getLong("failed"), rs.getLong("total"), decimal(rs, "avg_ms"),
                decimal(rs, "p95_ms")),
                tenantId, Timestamp.from(from), Timestamp.from(to));
    }

    public List<MethodReport> byMethod(long tenantId, Instant from, Instant to) {
        return jdbc.query("""
                SELECT coalesce(c.positioning_method, 'UNATTRIBUTED') AS name,
                       count(*) AS n,
                       count(*) FILTER (WHERE c.record_status = 'ANSWERED') AS answered,
                       avg(c.response_time_ms) FILTER (WHERE c.record_status = 'ANSWERED') AS avg_ms,
                       avg(c.uncertainty_m) FILTER (WHERE c.record_status = 'ANSWERED')
                           AS avg_uncertainty_m,
                       avg(c.age_of_location_s) FILTER (WHERE c.record_status = 'ANSWERED')
                           AS avg_age_s
                FROM cdr c
                WHERE c.tenant_id = ? AND c.created_at >= ? AND c.created_at < ?
                GROUP BY 1 ORDER BY n DESC, name LIMIT 50
                """, (rs, n) -> new MethodReport(rs.getString("name"), rs.getLong("n"),
                rs.getLong("answered"), decimal(rs, "avg_ms"), decimal(rs, "avg_uncertainty_m"),
                decimal(rs, "avg_age_s")),
                tenantId, Timestamp.from(from), Timestamp.from(to));
    }

    public List<CellReport> byCell(long tenantId, Instant from, Instant to) {
        return jdbc.query("""
                SELECT coalesce(lr.serving_cell, 'UNATTRIBUTED') AS name,
                       count(*) AS results,
                       avg(lr.uncertainty_m) AS avg_uncertainty_m,
                       percentile_cont(0.95) WITHIN GROUP (ORDER BY lr.uncertainty_m)
                           AS p95_uncertainty_m,
                       avg(lr.age_seconds) AS avg_age_s,
                       count(*) FILTER (WHERE lr.accuracy_fulfilled) AS accuracy_ok
                FROM location_result lr
                WHERE lr.tenant_id = ? AND lr.created_at >= ? AND lr.created_at < ?
                GROUP BY 1 ORDER BY results DESC, name LIMIT 100
                """, (rs, n) -> {
            long results = rs.getLong("results");
            long accuracyOk = rs.getLong("accuracy_ok");
            return new CellReport(rs.getString("name"), results, decimal(rs, "avg_uncertainty_m"),
                    decimal(rs, "p95_uncertainty_m"), decimal(rs, "avg_age_s"),
                    results == 0 ? null : (double) accuracyOk / results);
        }, tenantId, Timestamp.from(from), Timestamp.from(to));
    }

    // ----------------------------------------------------------------- records

    public record ExchangeFilter(String protocol, String recordStatus, UUID requestId) {
    }

    public record ExchangeRow(long cdrId, Instant createdAt, UUID requestId, UUID clientId,
                              String clientName, String protocol, String operation,
                              String recordStatus, String resultCode, String rat, String method,
                              List<String> methodsAttempted, Integer responseTimeMs,
                              boolean pagingPerformed, String servingNode, String positioningNode,
                              Integer uncertaintyM, Integer confidence, Integer ageSeconds,
                              Integer accuracyRequestedM, Integer accuracyAchievedM,
                              Boolean accuracyFulfilled, String shape, BigDecimal latitude,
                              BigDecimal longitude) {
    }

    public record ExchangeBucket(Instant bucket, long answered, long failed,
                                 Double averageResponseMs, Double p95ResponseMs) {
    }

    public record ExchangeTotals(long finished, long answered, long failed,
                                 Double averageResponseMs, Double p50ResponseMs,
                                 Double p95ResponseMs) {
    }

    public record NameCount(String name, long count) {
    }

    public record ClientReport(String name, long requests, long finished, long answered,
                               long failed, long inFlight, Double successRate,
                               Double averageResponseMs, Double p95ResponseMs) {
    }

    public record RatReport(String name, long answered, long failed, long total,
                            Double averageResponseMs, Double p95ResponseMs) {
    }

    public record MethodReport(String name, long count, long answered, Double averageResponseMs,
                               Double averageUncertaintyM, Double averageAgeSeconds) {
    }

    public record CellReport(String name, long results, Double averageUncertaintyM,
                             Double p95UncertaintyM, Double averageAgeSeconds,
                             Double accuracyFulfilledRate) {
    }

    // ----------------------------------------------------------------- helpers

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
