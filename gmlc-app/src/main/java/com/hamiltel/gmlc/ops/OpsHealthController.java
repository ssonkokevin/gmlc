package com.hamiltel.gmlc.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamiltel.gmlc.audit.AuditTrail;
import com.hamiltel.gmlc.config.ConfigService;
import com.hamiltel.gmlc.config.OpsAlertProperties;
import com.hamiltel.gmlc.config.OpsProperties;
import com.hamiltel.gmlc.diameter.DiameterProperties;
import com.hamiltel.gmlc.ss7.M3uaProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Signalling health and stored alerts. A peer is UP or DOWN only from exchanges
 * the gateway actually attempted: a face with no traffic is IDLE, and an
 * unconfigured face says NOT_CONFIGURED instead of pretending to be healthy.
 * Acknowledging an alert records the person; it does not close the condition,
 * which stays open until the next evaluation no longer observes it.
 */
@RestController
@RequestMapping("/v1/ops")
public class OpsHealthController {

    private static final int MAX_LIMIT = 200;
    private static final int DEFAULT_LIMIT = 50;

    private final PeerHealth health;
    private final OpsAlertRepository alerts;
    private final OpsAlertProperties alertProperties;
    private final OpsProperties ops;
    private final DiameterProperties diameter;
    private final M3uaProperties m3ua;
    private final AuditTrail audit;
    private final ObjectMapper mapper;
    private final ConfigService config;

    public OpsHealthController(PeerHealth health, OpsAlertRepository alerts,
                               OpsAlertProperties alertProperties, OpsProperties ops,
                               DiameterProperties diameter, M3uaProperties m3ua,
                               AuditTrail audit, ObjectMapper mapper, ConfigService config) {
        this.health = health;
        this.alerts = alerts;
        this.alertProperties = alertProperties;
        this.ops = ops;
        this.diameter = diameter;
        this.m3ua = m3ua;
        this.audit = audit;
        this.mapper = mapper;
        this.config = config;
    }

    @GetMapping("/peers")
    @OpsRequires(OpsPermission.VIEW_DASHBOARD)
    public PeerPage peers(@OpsCurrentSession OpsContext context) {
        PeerView diameterPeer = view(PeerHealth.DIAMETER, diameter.configured(),
                diameter.peerHost(), diameter.peerPort());
        PeerView m3uaPeer = view(PeerHealth.M3UA, m3ua.configured(),
                m3ua.host(), m3ua.port());
        return new PeerPage(ops.normalisedEnvironment(), ops.dataSourceLabel(),
                settings(context.tenantId()), List.of(diameterPeer, m3uaPeer));
    }

    @GetMapping("/alerts")
    @OpsRequires(OpsPermission.VIEW_DASHBOARD)
    public AlertPage alerts(@OpsCurrentSession OpsContext context,
                            @RequestParam(defaultValue = "OPEN") String state,
                            @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new OpsBadRequestException("LIMIT_OUT_OF_RANGE",
                    "limit must be between 1 and " + MAX_LIMIT);
        }
        OpsAlertRepository.State filter = parseState(state);
        long tenantId = context.tenantId();
        List<AlertView> rows = alerts.list(tenantId, filter, limit).stream()
                .map(this::view)
                .toList();
        return new AlertPage(filter.name(), limit, alertProperties.enabled(),
                alerts.openCount(tenantId), alerts.acknowledgedOpenCount(tenantId), rows);
    }

    @PostMapping("/alerts/{alertId}/acknowledge")
    @OpsRequires(OpsPermission.ACK_ALERTS)
    public AlertView acknowledge(@OpsCurrentSession OpsContext context,
                                 @PathVariable long alertId) {
        OpsAlertRepository.AlertRow alert = alerts.find(context.tenantId(), alertId)
                .orElseThrow(() -> new OpsNotFoundException("alert"));
        if (alert.closedAt() != null) {
            throw new OpsConflictException("ALERT_CLOSED",
                    "the alert has already been closed");
        }
        if (alert.acknowledgedAt() != null) {
            throw new OpsConflictException("ALREADY_ACKNOWLEDGED",
                    "the alert has already been acknowledged");
        }
        if (!alerts.acknowledge(context.tenantId(), alertId, context.user().userId())) {
            // Another request changed the row between the read and the update.
            OpsAlertRepository.AlertRow current = alerts.find(context.tenantId(), alertId)
                    .orElseThrow(() -> new OpsNotFoundException("alert"));
            if (current.closedAt() != null) {
                throw new OpsConflictException("ALERT_CLOSED",
                        "the alert has already been closed");
            }
            throw new OpsConflictException("ALREADY_ACKNOWLEDGED",
                    "the alert has already been acknowledged");
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("alertId", alertId);
        detail.put("kind", alert.kind().name());
        detail.put("severity", alert.severity().name());
        audit.recordOperator(context.tenantId(), context.user().userId(),
                "OPS_ALERT_ACKNOWLEDGED", AuditTrail.Outcome.ALLOWED, detail);

        return view(alerts.find(context.tenantId(), alertId)
                .orElseThrow(() -> new OpsNotFoundException("alert")));
    }

    private PeerView view(String face, boolean configured, String host, int port) {
        if (!configured) {
            return new PeerView(face, false, null, null, "NOT_CONFIGURED",
                    0, 0, 0, 0, null, null, null, null);
        }
        PeerHealth.Snapshot snapshot = health.snapshot(face);
        String state = snapshot.attempts() == 0 ? "IDLE" : snapshot.failedLast() ? "DOWN" : "UP";
        return new PeerView(face, true, host, port, state, snapshot.attempts(),
                snapshot.successes(), snapshot.failures(), snapshot.consecutiveFailures(),
                snapshot.lastAttemptAt(), snapshot.lastSuccessAt(), snapshot.lastFailureAt(),
                snapshot.lastCode());
    }

    private AlertView view(OpsAlertRepository.AlertRow alert) {
        return new AlertView(alert.alertId(), alert.dedupeKey(), alert.kind().name(),
                alert.severity().name(), alert.summary(), alert.detail(mapper),
                alert.openedAt(), alert.lastSeenAt(), alert.closedAt(), alert.acknowledgedAt(),
                alert.acknowledgedBy(), alert.closedAt() == null,
                alert.acknowledgedAt() != null);
    }

    private AlertSettings settings(long tenantId) {
        int sloValue = config.intValue(tenantId, "alerts.sloP95Ms");
        Integer slo = sloValue > 0 ? sloValue : null;
        return new AlertSettings(alertProperties.enabled(), alertProperties.evalSeconds(),
                config.intValue(tenantId, "alerts.windowMinutes"),
                config.intValue(tenantId, "alerts.minFinished"),
                config.doubleValue(tenantId, "alerts.errorRateThreshold"),
                config.intValue(tenantId, "alerts.peerFailureThreshold"),
                config.intValue(tenantId, "alerts.cellGapThreshold"), slo);
    }

    private static OpsAlertRepository.State parseState(String value) {
        String upper = value == null ? "OPEN" : value.trim().toUpperCase(Locale.ROOT);
        try {
            return OpsAlertRepository.State.valueOf(upper);
        } catch (IllegalArgumentException e) {
            throw new OpsBadRequestException("ALERTS_STATE_NOT_SUPPORTED",
                    "state must be one of OPEN, ACKNOWLEDGED, CLOSED, ALL");
        }
    }

    // ----------------------------------------------------------------- records

    public record PeerView(String face, boolean configured, String host, Integer port,
                           String state, long attempts, long successes, long failures,
                           int consecutiveFailures, Instant lastAttemptAt,
                           Instant lastSuccessAt, Instant lastFailureAt, String lastCode) {
    }

    public record AlertSettings(boolean enabled, long evalSeconds, long windowMinutes,
                                long minFinished, double errorRateThreshold,
                                int peerFailureThreshold, long cellGapThreshold,
                                Integer sloP95Ms) {
    }

    public record PeerPage(String environment, String dataSource, AlertSettings settings,
                           List<PeerView> peers) {
    }

    public record AlertView(long alertId, String dedupeKey, String kind, String severity,
                            String summary, JsonNode detail, Instant openedAt,
                            Instant lastSeenAt, Instant closedAt, Instant acknowledgedAt,
                            UUID acknowledgedBy, boolean open, boolean acknowledged) {
    }

    public record AlertPage(String state, int limit, boolean evaluationEnabled, long open,
                            long acknowledgedOpen, List<AlertView> alerts) {
    }
}
