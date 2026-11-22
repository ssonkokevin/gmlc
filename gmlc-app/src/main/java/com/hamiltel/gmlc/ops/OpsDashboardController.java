package com.hamiltel.gmlc.ops;

import com.hamiltel.gmlc.audit.AuditTrail;
import com.hamiltel.gmlc.config.ConfigService;
import com.hamiltel.gmlc.config.OpsAlertProperties;
import com.hamiltel.gmlc.config.OpsProperties;
import com.hamiltel.gmlc.location.LocationResultRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The dashboard reads. Every number is a count or an aggregate over rows the
 * gateway stored; a feature that is not built says so instead of showing a
 * zero. Identifiers are masked unless the caller holds REVEAL_IDENTIFIERS, and
 * a reveal writes an audit row before the value is returned.
 *
 * <p>This surface is session-authenticated: it cannot be reached with a client
 * API key, does not consume a client quota and is not a substitute for the Le
 * API.
 */
@RestController
@RequestMapping("/v1/ops")
public class OpsDashboardController {

    private static final int MAX_LIMIT = 200;
    private static final int DEFAULT_LIMIT = 50;

    private static final String DEFERRED_REASON =
            "deferred location (LDR) is not implemented in this deployment; open requests"
                    + " are reported under inFlight";
    private static final String LICENCE_REASON =
            "licence metering is not wired in this deployment";
    private static final String FALLBACK_REASON =
            "the MAP locator chooses ATI or PSL from the SRI answer and never attempts both,"
                    + " so there is no fallback step to measure";
    private static final String ALERTS_DISABLED_REASON =
            "alert evaluation is disabled in this deployment (gmlc.ops.alerts.enabled=false)";

    private final OpsDashboardRepository dashboard;
    private final LocationResultRepository results;
    private final OpsAlertRepository alerts;
    private final AuditTrail audit;
    private final OpsProperties properties;
    private final OpsAlertProperties alertProperties;
    private final ConfigService config;

    public OpsDashboardController(OpsDashboardRepository dashboard,
                                  LocationResultRepository results,
                                  OpsAlertRepository alerts, AuditTrail audit,
                                  OpsProperties properties,
                                  OpsAlertProperties alertProperties, ConfigService config) {
        this.dashboard = dashboard;
        this.results = results;
        this.alerts = alerts;
        this.audit = audit;
        this.properties = properties;
        this.alertProperties = alertProperties;
        this.config = config;
    }

    @GetMapping("/summary")
    @OpsRequires(OpsPermission.VIEW_DASHBOARD)
    public Summary summary(@OpsCurrentSession OpsContext context,
                           @RequestParam(defaultValue = "24h") String range,
                           @RequestParam(required = false) String bucket) {
        OpsRange current = OpsRange.parse(range, bucket);
        OpsRange previous = current.previous();

        OpsDashboardRepository.Totals totals =
                dashboard.totals(context.tenantId(), current.from(), current.to());
        OpsDashboardRepository.Totals before =
                dashboard.totals(context.tenantId(), previous.from(), previous.to());

        List<SeriesPoint> series = dashboard.series(context.tenantId(), current).stream()
                .map(point -> new SeriesPoint(point.bucket(), point.requests(), point.answered(),
                        point.failed(), point.averageResponseMs(), point.p95ResponseMs(),
                        point.answered() + point.failed() == 0 ? null
                                : (double) point.answered()
                                        / (point.answered() + point.failed())))
                .toList();

        int sloValue = config.intValue(context.tenantId(), "alerts.sloP95Ms");
        Integer slo = sloValue > 0 ? sloValue : null;

        return new Summary(current.key(), current.from(), current.to(),
                properties.normalisedEnvironment(), properties.dataSourceLabel(),
                totals.requests(), before.requests(),
                totals.finished(), totals.answered(), totals.failed(), totals.inFlight(),
                totals.successRate(), before.successRate(),
                totals.averageResponseMs(), before.averageResponseMs(),
                totals.p50ResponseMs(),
                totals.p95ResponseMs(), before.p95ResponseMs(),
                totals.accuracyFulfilledRate(),
                slo,
                new Feature(false, DEFERRED_REASON),
                alertsState(context.tenantId()),
                new Feature(false, LICENCE_REASON),
                series);
    }

    private AlertState alertsState(long tenantId) {
        if (!alertProperties.enabled()) {
            return new AlertState(false, 0, 0, ALERTS_DISABLED_REASON);
        }
        return new AlertState(true, alerts.openCount(tenantId),
                alerts.acknowledgedOpenCount(tenantId), null);
    }

    @GetMapping("/throughput")
    @OpsRequires(OpsPermission.VIEW_DASHBOARD)
    public Throughput throughput(@OpsCurrentSession OpsContext context,
                                 @RequestParam(defaultValue = "24h") String range,
                                 @RequestParam(required = false) String bucket) {
        OpsRange window = OpsRange.parse(range, bucket);
        List<OpsDashboardRepository.ProtocolPoint> points =
                dashboard.throughput(context.tenantId(), window);
        long map = points.stream().mapToLong(OpsDashboardRepository.ProtocolPoint::mapRequests).sum();
        long diameter = points.stream()
                .mapToLong(OpsDashboardRepository.ProtocolPoint::diameterRequests).sum();
        long unattributed = points.stream()
                .mapToLong(OpsDashboardRepository.ProtocolPoint::unattributed).sum();
        return new Throughput(window.key(), window.from(), window.to(),
                window.bucket().getSeconds(), points,
                new ProtocolTotals(map, diameter, unattributed, map + diameter + unattributed));
    }

    @GetMapping("/mix")
    @OpsRequires(OpsPermission.VIEW_DASHBOARD)
    public Mix mix(@OpsCurrentSession OpsContext context,
                   @RequestParam(defaultValue = "24h") String range,
                   @RequestParam(required = false) String bucket) {
        OpsRange window = OpsRange.parse(range, bucket);
        long tenantId = context.tenantId();
        List<OpsDashboardRepository.NameCount> byOperation =
                dashboard.byOperation(tenantId, window.from(), window.to());
        List<OpsDashboardRepository.NameCount> byMethod =
                dashboard.byMethod(tenantId, window.from(), window.to());
        List<OpsDashboardRepository.NameCount> byRat =
                dashboard.byRat(tenantId, window.from(), window.to());
        List<OpsDashboardRepository.NameCount> byResult =
                dashboard.byResult(tenantId, window.from(), window.to());
        return new Mix(window.key(), window.from(), window.to(), properties.dataSourceLabel(),
                byOperation, byMethod, byRat, byResult,
                new Feature(false, FALLBACK_REASON));
    }

    @GetMapping("/deferred")
    @OpsRequires(OpsPermission.VIEW_DASHBOARD)
    public Deferred deferred(@OpsCurrentSession OpsContext context) {
        OpsDashboardRepository.InFlight inFlight = dashboard.inFlight(context.tenantId());
        return new Deferred(false, DEFERRED_REASON, inFlight.open(), inFlight.pastDeadline());
    }

    @GetMapping("/requests")
    @OpsRequires(OpsPermission.VIEW_REQUESTS)
    public RequestPage requests(@OpsCurrentSession OpsContext context,
                                @RequestParam(defaultValue = "24h") String range,
                                @RequestParam(required = false) String bucket,
                                @RequestParam(required = false) String state,
                                @RequestParam(required = false) String protocol,
                                @RequestParam(required = false) String purpose,
                                @RequestParam(required = false) UUID clientId,
                                @RequestParam(required = false) String recordStatus,
                                @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit,
                                @RequestParam(defaultValue = "0") int offset) {
        OpsRange window = OpsRange.parse(range, bucket);
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new OpsBadRequestException("LIMIT_OUT_OF_RANGE",
                    "limit must be between 1 and " + MAX_LIMIT);
        }
        if (offset < 0) {
            throw new OpsBadRequestException("OFFSET_OUT_OF_RANGE", "offset must be zero or more");
        }
        String protocolFilter = upperOrNull(protocol, "protocol", "MAP", "DIAMETER");
        String statusFilter = upperOrNull(recordStatus, "recordStatus",
                "ANSWERED", "FAILED", "TIMEOUT", "ABORTED");

        OpsDashboardRepository.RequestFilter filter = new OpsDashboardRepository.RequestFilter(
                upperOrNull(state, "state", "RECEIVED", "AUTHORISED", "PRIVACY_CHECKED", "ROUTED",
                        "REQUESTED", "ANSWERED", "TIMEOUT", "FAILED", "DELIVERED"),
                protocolFilter, purpose, clientId, statusFilter);

        long total = dashboard.requestCount(context.tenantId(), window.from(), window.to(), filter);
        List<RequestItem> items = dashboard
                .requests(context.tenantId(), window, filter, limit, offset).stream()
                .map(row -> new RequestItem(row.requestId(), row.createdAt(), row.completedAt(),
                        row.clientId(), row.clientName(), row.purpose(),
                        OpsMasking.mask(row.msisdn()), OpsMasking.mask(row.imsi()),
                        row.state(), row.errorCode(), row.protocol(), row.operation(),
                        row.recordStatus(), row.resultCode(), row.method(), row.rat(),
                        row.uncertaintyM(), row.confidence(), row.ageSeconds(),
                        row.responseTimeMs(), row.accuracyFulfilled(), row.accuracyRequestedM(),
                        row.latitude(), row.longitude(), row.shape(), row.servingNode()))
                .toList();

        return new RequestPage(window.key(), window.from(), window.to(), limit, offset, total,
                true, properties.dataSourceLabel(), items);
    }

    @GetMapping("/requests/{requestId}")
    @OpsRequires(OpsPermission.VIEW_REQUESTS)
    public Detail request(@OpsCurrentSession OpsContext context,
                          @PathVariable UUID requestId,
                          @RequestParam(defaultValue = "false") boolean reveal) {
        OpsDashboardRepository.RequestDetail request = dashboard
                .request(context.tenantId(), requestId)
                .orElseThrow(() -> new OpsNotFoundException("request"));

        boolean revealed = reveal && authoriseReveal(context, requestId);

        OpsDashboardRepository.CdrDetail cdr =
                dashboard.cdr(context.tenantId(), requestId).orElse(null);
        LocationResultRepository.StoredResult result = request.resultId() == null ? null
                : results.find(context.tenantId(), request.resultId()).orElse(null);
        List<OpsDashboardRepository.TimelineEvent> timeline =
                dashboard.timeline(context.tenantId(), requestId, request.createdAt(), cdr);

        Identifier identifiers = new Identifier(
                revealed ? request.msisdn() : OpsMasking.mask(request.msisdn()),
                revealed ? request.imsi() : OpsMasking.mask(request.imsi()),
                !revealed);

        return new Detail(request.requestId(), request.clientId(), request.clientName(),
                request.purpose(), request.state(), request.errorCode(), request.errorDetail(),
                request.protocol(), request.rat(), request.servingNode(),
                request.positioningNode(), request.accuracyRequestedM(), request.priority(),
                request.createdAt(), request.updatedAt(), request.deadlineAt(),
                request.completedAt(), request.resultId(),
                properties.normalisedEnvironment(), properties.dataSourceLabel(),
                identifiers, result, cdr, timeline);
    }

    /**
     * A reveal is a permission, not a courtesy: a role without it is refused
     * and both outcomes land in the trail. The identifier itself is never
     * written to the trail.
     */
    private boolean authoriseReveal(OpsContext context, UUID requestId) {
        Map<String, Object> detail = Map.of("requestId", requestId.toString());
        if (!context.allows(OpsPermission.REVEAL_IDENTIFIERS)) {
            audit.recordOperator(context.tenantId(), context.user().userId(),
                    "OPS_IDENTIFIER_REVEAL", AuditTrail.Outcome.DENIED, detail);
            throw new OpsForbiddenException(OpsPermission.REVEAL_IDENTIFIERS);
        }
        audit.recordOperator(context.tenantId(), context.user().userId(),
                "OPS_IDENTIFIER_REVEAL", AuditTrail.Outcome.ALLOWED, detail);
        return true;
    }

    private static String upperOrNull(String value, String name, String... allowed) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String upper = value.trim().toUpperCase(Locale.ROOT);
        for (String candidate : allowed) {
            if (candidate.equals(upper)) {
                return upper;
            }
        }
        throw new OpsBadRequestException(name.toUpperCase(Locale.ROOT) + "_NOT_SUPPORTED",
                name + " must be one of " + List.of(allowed));
    }

    // ----------------------------------------------------------------- records

    public record Feature(boolean available, String reason) {
    }

    public record AlertState(boolean available, long open, long acknowledgedOpen, String reason) {
    }

    public record SeriesPoint(Instant bucket, long requests, long answered, long failed,
                              Double averageResponseMs, Double p95ResponseMs,
                              Double successRate) {
    }

    public record Summary(
            String range,
            Instant from,
            Instant to,
            String environment,
            String dataSource,
            long requests,
            long previousRequests,
            long finished,
            long answered,
            long failed,
            long inFlight,
            Double successRate,
            Double previousSuccessRate,
            Double averageResponseMs,
            Double previousAverageResponseMs,
            Double p50ResponseMs,
            Double p95ResponseMs,
            Double previousP95ResponseMs,
            Double accuracyFulfilledRate,
            Integer sloP95Ms,
            Feature deferred,
            AlertState alerts,
            Feature licence,
            List<SeriesPoint> series) {
    }

    public record Throughput(String range, Instant from, Instant to, long bucketSeconds,
                             List<OpsDashboardRepository.ProtocolPoint> points,
                             ProtocolTotals totals) {
    }

    public record ProtocolTotals(long map, long diameter, long unattributed, long total) {
    }

    public record Mix(String range, Instant from, Instant to, String dataSource,
                      List<OpsDashboardRepository.NameCount> byOperation,
                      List<OpsDashboardRepository.NameCount> byMethod,
                      List<OpsDashboardRepository.NameCount> byRat,
                      List<OpsDashboardRepository.NameCount> byResult,
                      Feature fallback) {
    }

    public record Deferred(boolean available, String reason, long open, long pastDeadline) {
    }

    public record RequestPage(String range, Instant from, Instant to, int limit, int offset,
                              long total, boolean identifiersMasked, String dataSource,
                              List<RequestItem> items) {
    }

    public record RequestItem(UUID requestId, Instant createdAt, Instant completedAt,
                              UUID clientId, String clientName, String purpose,
                              String msisdn, String imsi, String state, String errorCode,
                              String protocol, String operation, String recordStatus,
                              String resultCode, String method, String rat, Integer uncertaintyM,
                              Integer confidence, Integer ageSeconds, Integer responseTimeMs,
                              Boolean accuracyFulfilled, Integer accuracyRequestedM,
                              BigDecimal latitude, BigDecimal longitude, String shape,
                              String servingNode) {
    }

    public record Identifier(String msisdn, String imsi, boolean masked) {
    }

    public record Detail(UUID requestId, UUID clientId, String clientName, String purpose,
                         String state, String errorCode, String errorDetail, String protocol,
                         String rat, String servingNode, String positioningNode,
                         Integer accuracyRequestedM, String priority, Instant createdAt,
                         Instant updatedAt, Instant deadlineAt, Instant completedAt,
                         UUID resultId, String environment, String dataSource,
                         Identifier identifiers, LocationResultRepository.StoredResult result,
                         OpsDashboardRepository.CdrDetail cdr,
                         List<OpsDashboardRepository.TimelineEvent> timeline) {
    }
}
