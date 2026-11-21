package com.hamiltel.gmlc.ops;

import com.hamiltel.gmlc.audit.AuditTrail;
import com.hamiltel.gmlc.config.OpsProperties;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.PrintWriter;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Monitoring &amp; Tracing and Reports over the rows the gateway stored.
 *
 * <p>The exchange list is the CDR: one row per signalling attempt, with the
 * protocol, operation, result code, nodes and timing the gateway recorded. Raw
 * message payloads are not captured in this deployment and the response says
 * so rather than offering an empty inspector.
 *
 * <p>Exports never contain a subscriber identifier. They carry the request id,
 * which is enough to join to the request detail under its own permission and
 * reveal audit, and they are themselves audited with the row count.
 */
@RestController
@RequestMapping("/v1/ops")
public class OpsMonitoringController {

    private static final int MAX_LIMIT = 200;
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_EXPORT_ROWS = 50_000;

    private static final String RAW_PAYLOAD_REASON =
            "raw signalling payloads are not captured in this deployment;"
                    + " the exchange record names what the gateway actually sent and received";

    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final OpsMonitoringRepository monitoring;
    private final OpsDashboardRepository dashboard;
    private final OpsProperties properties;
    private final AuditTrail audit;

    public OpsMonitoringController(OpsMonitoringRepository monitoring,
                                   OpsDashboardRepository dashboard, OpsProperties properties,
                                   AuditTrail audit) {
        this.monitoring = monitoring;
        this.dashboard = dashboard;
        this.properties = properties;
        this.audit = audit;
    }

    // -------------------------------------------------------------- exchanges

    @GetMapping("/exchanges")
    @OpsRequires(OpsPermission.VIEW_REQUESTS)
    public ExchangePage exchanges(@OpsCurrentSession OpsContext context,
                                  @RequestParam(defaultValue = "24h") String range,
                                  @RequestParam(required = false) String protocol,
                                  @RequestParam(required = false) String recordStatus,
                                  @RequestParam(required = false) UUID requestId,
                                  @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit,
                                  @RequestParam(defaultValue = "0") int offset) {
        OpsRange window = OpsRange.parse(range, null);
        requirePage(limit, offset);
        OpsMonitoringRepository.ExchangeFilter filter = filter(protocol, recordStatus, requestId);

        long total = monitoring.exchangeCount(context.tenantId(), window.from(), window.to(), filter);
        List<OpsMonitoringRepository.ExchangeRow> rows = monitoring
                .exchanges(context.tenantId(), window.from(), window.to(), filter, limit, offset);

        return new ExchangePage(window.key(), window.from(), window.to(), limit, offset, total,
                properties.dataSourceLabel(), new Feature(false, RAW_PAYLOAD_REASON), rows);
    }

    @GetMapping("/exchanges/series")
    @OpsRequires(OpsPermission.VIEW_REQUESTS)
    public ExchangeSeries exchangeSeries(@OpsCurrentSession OpsContext context,
                                         @RequestParam(defaultValue = "24h") String range,
                                         @RequestParam(required = false) String bucket,
                                         @RequestParam(required = false) String protocol) {
        OpsRange window = OpsRange.parse(range, bucket);
        String face = upperOrNull(protocol, "protocol", "MAP", "DIAMETER");
        long tenantId = context.tenantId();
        return new ExchangeSeries(window.key(), window.from(), window.to(),
                window.bucket().getSeconds(), face, properties.dataSourceLabel(),
                monitoring.exchangeSeries(tenantId, window, face),
                monitoring.exchangeTotals(tenantId, window, face),
                monitoring.exchangeProtocols(tenantId, window));
    }

    @GetMapping(value = "/exchanges/export.csv", produces = "text/csv")
    @OpsRequires(OpsPermission.EXPORT_CDR)
    public void exportExchanges(@OpsCurrentSession OpsContext context,
                                @RequestParam(defaultValue = "24h") String range,
                                @RequestParam(required = false) String protocol,
                                @RequestParam(required = false) String recordStatus,
                                @RequestParam(required = false) UUID requestId,
                                HttpServletResponse response) throws IOException {
        OpsRange window = OpsRange.parse(range, null);
        OpsMonitoringRepository.ExchangeFilter filter = filter(protocol, recordStatus, requestId);
        long tenantId = context.tenantId();

        long total = monitoring.exchangeCount(tenantId, window.from(), window.to(), filter);
        int rows = (int) Math.min(total, MAX_EXPORT_ROWS);
        List<OpsMonitoringRepository.ExchangeRow> exchanges = monitoring
                .exchanges(tenantId, window.from(), window.to(), filter, Math.max(rows, 1), 0);
        boolean truncated = total > MAX_EXPORT_ROWS;

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("range", window.key());
        detail.put("protocol", filter.protocol());
        detail.put("recordStatus", filter.recordStatus());
        detail.put("requestId", filter.requestId() == null ? null
                : filter.requestId().toString());
        detail.put("rows", rows);
        detail.put("truncated", truncated);
        audit.recordOperator(tenantId, context.user().userId(), "OPS_CDR_EXPORTED",
                AuditTrail.Outcome.ALLOWED, detail);

        prepareCsv(response, "exchanges", window);
        response.setHeader("X-Export-Rows", Integer.toString(rows));
        response.setHeader("X-Export-Truncated", Boolean.toString(truncated));

        PrintWriter out = response.getWriter();
        out.println(csv("cdr_id", "created_at", "request_id", "client_id", "client_name",
                "protocol", "operation", "record_status", "result_code", "rat_type",
                "positioning_method", "methods_attempted", "response_time_ms", "paging_performed",
                "serving_node", "positioning_node", "uncertainty_m", "confidence",
                "age_of_location_s", "accuracy_requested_m", "accuracy_achieved_m",
                "accuracy_fulfilled", "shape", "latitude", "longitude"));
        for (OpsMonitoringRepository.ExchangeRow row : exchanges) {
            out.println(csv(row.cdrId(), row.createdAt(), row.requestId(), row.clientId(),
                    row.clientName(), row.protocol(), row.operation(), row.recordStatus(),
                    row.resultCode(), row.rat(), row.method(),
                    String.join(";", row.methodsAttempted()), row.responseTimeMs(),
                    row.pagingPerformed(), row.servingNode(), row.positioningNode(),
                    row.uncertaintyM(), row.confidence(), row.ageSeconds(),
                    row.accuracyRequestedM(), row.accuracyAchievedM(), row.accuracyFulfilled(),
                    row.shape(), row.latitude(), row.longitude()));
        }
    }

    // ---------------------------------------------------------------- reports

    @GetMapping("/reports")
    @OpsRequires(OpsPermission.VIEW_DASHBOARD)
    public Reports reports(@OpsCurrentSession OpsContext context,
                           @RequestParam(defaultValue = "24h") String range) {
        OpsRange window = OpsRange.parse(range, null);
        long tenantId = context.tenantId();
        OpsDashboardRepository.Totals totals =
                dashboard.totals(tenantId, window.from(), window.to());

        return new Reports(window.key(), window.from(), window.to(),
                properties.normalisedEnvironment(), properties.dataSourceLabel(),
                new ReportTotals(totals.requests(), totals.finished(), totals.answered(),
                        totals.failed(), totals.inFlight(), totals.averageResponseMs(),
                        totals.p50ResponseMs(), totals.p95ResponseMs(), totals.successRate(),
                        totals.accuracyFulfilledRate()),
                monitoring.byClient(tenantId, window.from(), window.to()),
                monitoring.byPurpose(tenantId, window.from(), window.to()),
                monitoring.byRat(tenantId, window.from(), window.to()),
                monitoring.byMethod(tenantId, window.from(), window.to()),
                monitoring.byCell(tenantId, window.from(), window.to()),
                List.of("Client and purpose rows are grouped at request creation; a request"
                                + " with no CDR yet counts as in flight.",
                        "RAT and method rows are grouped at CDR write time; success means"
                                + " record_status ANSWERED, as on the dashboard.",
                        "Cell rows are grouped from stored location results by serving cell;"
                                + " a result that names no cell appears as UNATTRIBUTED."));
    }

    @GetMapping(value = "/reports/export.csv", produces = "text/csv")
    @OpsRequires(OpsPermission.EXPORT_CDR)
    public void exportReport(@OpsCurrentSession OpsContext context,
                             @RequestParam(defaultValue = "24h") String range,
                             @RequestParam(defaultValue = "clients") String report,
                             HttpServletResponse response) throws IOException {
        OpsRange window = OpsRange.parse(range, null);
        long tenantId = context.tenantId();
        String section = normaliseReport(report);

        audit.recordOperator(tenantId, context.user().userId(), "OPS_REPORT_EXPORTED",
                AuditTrail.Outcome.ALLOWED, Map.of("range", window.key(), "report", section));

        prepareCsv(response, "report-" + section, window);
        PrintWriter out = response.getWriter();
        switch (section) {
            case "clients" -> {
                out.println(csv("name", "requests", "finished", "answered", "failed",
                        "in_flight", "success_rate", "average_response_ms", "p95_response_ms"));
                for (OpsMonitoringRepository.ClientReport row
                        : monitoring.byClient(tenantId, window.from(), window.to())) {
                    out.println(csv(row.name(), row.requests(), row.finished(), row.answered(),
                            row.failed(), row.inFlight(), row.successRate(),
                            row.averageResponseMs(), row.p95ResponseMs()));
                }
            }
            case "purposes" -> {
                out.println(csv("name", "requests", "finished", "answered", "failed",
                        "in_flight", "success_rate", "average_response_ms", "p95_response_ms"));
                for (OpsMonitoringRepository.ClientReport row
                        : monitoring.byPurpose(tenantId, window.from(), window.to())) {
                    out.println(csv(row.name(), row.requests(), row.finished(), row.answered(),
                            row.failed(), row.inFlight(), row.successRate(),
                            row.averageResponseMs(), row.p95ResponseMs()));
                }
            }
            case "rat" -> {
                out.println(csv("name", "answered", "failed", "total",
                        "average_response_ms", "p95_response_ms"));
                for (OpsMonitoringRepository.RatReport row
                        : monitoring.byRat(tenantId, window.from(), window.to())) {
                    out.println(csv(row.name(), row.answered(), row.failed(), row.total(),
                            row.averageResponseMs(), row.p95ResponseMs()));
                }
            }
            case "method" -> {
                out.println(csv("name", "count", "answered", "average_response_ms",
                        "average_uncertainty_m", "average_age_s"));
                for (OpsMonitoringRepository.MethodReport row
                        : monitoring.byMethod(tenantId, window.from(), window.to())) {
                    out.println(csv(row.name(), row.count(), row.answered(),
                            row.averageResponseMs(), row.averageUncertaintyM(),
                            row.averageAgeSeconds()));
                }
            }
            default -> {
                out.println(csv("name", "results", "average_uncertainty_m", "p95_uncertainty_m",
                        "average_age_s", "accuracy_fulfilled_rate"));
                for (OpsMonitoringRepository.CellReport row
                        : monitoring.byCell(tenantId, window.from(), window.to())) {
                    out.println(csv(row.name(), row.results(), row.averageUncertaintyM(),
                            row.p95UncertaintyM(), row.averageAgeSeconds(),
                            row.accuracyFulfilledRate()));
                }
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private static void requirePage(int limit, int offset) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new OpsBadRequestException("LIMIT_OUT_OF_RANGE",
                    "limit must be between 1 and " + MAX_LIMIT);
        }
        if (offset < 0) {
            throw new OpsBadRequestException("OFFSET_OUT_OF_RANGE", "offset must be zero or more");
        }
    }

    private static OpsMonitoringRepository.ExchangeFilter filter(String protocol,
                                                                String recordStatus,
                                                                UUID requestId) {
        return new OpsMonitoringRepository.ExchangeFilter(
                upperOrNull(protocol, "protocol", "MAP", "DIAMETER"),
                upperOrNull(recordStatus, "recordStatus",
                        "ANSWERED", "FAILED", "TIMEOUT", "ABORTED"),
                requestId);
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

    private static String normaliseReport(String value) {
        String section = value == null ? "clients" : value.trim().toLowerCase(Locale.ROOT);
        if (List.of("clients", "purposes", "rat", "method", "cell").contains(section)) {
            return section;
        }
        throw new OpsBadRequestException("REPORT_NOT_SUPPORTED",
                "report must be one of [clients, purposes, rat, method, cell]");
    }

    private static void prepareCsv(HttpServletResponse response, String name, OpsRange window) {
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\""
                + name + "-" + window.key() + "-" + FILE_STAMP.format(Instant.now()) + ".csv\"");
        response.setHeader("Cache-Control", "no-store");
    }

    /**
     * Quotes every field and neutralises a leading =, +, - or @ so a stored
     * name cannot become a formula when the file is opened in a spreadsheet.
     */
    private static String csv(Object... cells) {
        List<String> out = new ArrayList<>(cells.length);
        for (Object value : cells) {
            String text = value == null ? "" : value instanceof Instant instant
                    ? instant.toString() : String.valueOf(value);
            if (!text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0) {
                text = "'" + text;
            }
            out.add('"' + text.replace("\"", "\"\"") + '"');
        }
        return String.join(",", out);
    }

    // ---------------------------------------------------------------- records

    public record Feature(boolean available, String reason) {
    }

    public record ExchangePage(String range, Instant from, Instant to, int limit, int offset,
                               long total, String dataSource, Feature rawPayload,
                               List<OpsMonitoringRepository.ExchangeRow> items) {
    }

    public record ExchangeSeries(String range, Instant from, Instant to, long bucketSeconds,
                                 String protocol, String dataSource,
                                 List<OpsMonitoringRepository.ExchangeBucket> points,
                                 OpsMonitoringRepository.ExchangeTotals totals,
                                 List<OpsMonitoringRepository.NameCount> byProtocol) {
    }

    public record ReportTotals(long requests, long finished, long answered, long failed,
                               long inFlight, Double averageResponseMs, Double p50ResponseMs,
                               Double p95ResponseMs, Double successRate,
                               Double accuracyFulfilledRate) {
    }

    public record Reports(String range, Instant from, Instant to, String environment,
                          String dataSource, ReportTotals totals,
                          List<OpsMonitoringRepository.ClientReport> byClient,
                          List<OpsMonitoringRepository.ClientReport> byPurpose,
                          List<OpsMonitoringRepository.RatReport> byRat,
                          List<OpsMonitoringRepository.MethodReport> byMethod,
                          List<OpsMonitoringRepository.CellReport> byCell,
                          List<String> notes) {
    }
}
