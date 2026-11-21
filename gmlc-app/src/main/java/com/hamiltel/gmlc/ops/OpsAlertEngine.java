package com.hamiltel.gmlc.ops;

import com.hamiltel.gmlc.config.OpsAlertProperties;
import com.hamiltel.gmlc.config.OpsProperties;
import com.hamiltel.gmlc.diameter.DiameterProperties;
import com.hamiltel.gmlc.ss7.M3uaProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Evaluates four conditions from recorded data and keeps one open alert row per
 * condition. It does not probe peers: a peer is DOWN after the transport
 * wrapper has seen consecutive failures, which is evidence, not a guess. An
 * evaluation that observes nothing wrong closes what it previously opened.
 *
 * <p>Thresholds live in {@code gmlc.ops.alerts}; the latency objective is the
 * deployment's own SLO, and no latency alert exists while that SLO is zero.
 */
@Component
public class OpsAlertEngine {

    private static final Logger log = LoggerFactory.getLogger(OpsAlertEngine.class);

    static final String PEER_PREFIX = "PEER_DOWN:";
    static final String ERROR_RATE = "ERROR_RATE";
    static final String LATENCY = "LATENCY_SLO";
    static final String CELL_GAP = "CELL_DB_GAP";

    private final JdbcTemplate jdbc;
    private final OpsDashboardRepository dashboard;
    private final OpsAlertRepository alerts;
    private final PeerHealth peerHealth;
    private final DiameterProperties diameter;
    private final M3uaProperties m3ua;
    private final OpsProperties ops;
    private final OpsAlertProperties properties;

    public OpsAlertEngine(JdbcTemplate jdbc, OpsDashboardRepository dashboard,
                          OpsAlertRepository alerts, PeerHealth peerHealth,
                          DiameterProperties diameter, M3uaProperties m3ua, OpsProperties ops,
                          OpsAlertProperties properties) {
        this.jdbc = jdbc;
        this.dashboard = dashboard;
        this.alerts = alerts;
        this.peerHealth = peerHealth;
        this.diameter = diameter;
        this.m3ua = m3ua;
        this.ops = ops;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${gmlc.ops.alerts.eval-seconds:60}",
            initialDelayString = "${gmlc.ops.alerts.eval-seconds:60}",
            timeUnit = java.util.concurrent.TimeUnit.SECONDS)
    public void scheduledEvaluation() {
        if (!properties.enabled()) {
            return;
        }
        try {
            evaluate();
        } catch (RuntimeException e) {
            // An evaluation that cannot read the database must not take the
            // gateway down; the next tick tries again and open alerts stay open.
            log.warn("alert evaluation skipped: {}", e.getClass().getSimpleName());
        }
    }

    public void evaluate() {
        Instant now = Instant.now();
        for (Long tenantId : jdbc.queryForList(
                "SELECT tenant_id FROM tenant WHERE active ORDER BY tenant_id", Long.class)) {
            evaluate(tenantId, now);
        }
    }

    /** Package-visible so a test can evaluate one tenant at a chosen instant. */
    void evaluate(long tenantId, Instant now) {
        Instant from = now.minus(Duration.ofMinutes(properties.windowMinutes()));
        Set<String> open = new LinkedHashSet<>();

        peerDown(tenantId, PeerHealth.DIAMETER, PeerHealth.DIAMETER, open);
        peerDown(tenantId, PeerHealth.M3UA, PeerHealth.M3UA, open);

        OpsDashboardRepository.Totals totals = dashboard.totals(tenantId, from, now);
        errorRate(tenantId, totals, open);
        latency(tenantId, totals, open);

        long cellGaps = dashboard.cellGapFailures(tenantId, from, now);
        if (cellGaps >= properties.cellGapThreshold()) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("requests", cellGaps);
            detail.put("windowMinutes", properties.windowMinutes());
            alerts.raise(tenantId, CELL_GAP, OpsAlertRepository.Kind.CELL_DB_GAP,
                    OpsAlertRepository.Severity.WARNING,
                    cellGaps + " request(s) failed because the cell plan cannot answer", detail);
            open.add(CELL_GAP);
        }

        alerts.closeExcept(tenantId, open);
    }

    private void peerDown(long tenantId, String face, String label, Set<String> open) {
        if (!configured(face)) {
            return;
        }
        PeerHealth.Snapshot snapshot = peerHealth.snapshot(face);
        if (snapshot.consecutiveFailures() < properties.peerFailureThreshold()) {
            return;
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("face", face);
        detail.put("consecutiveFailures", snapshot.consecutiveFailures());
        detail.put("lastCode", snapshot.lastCode());
        detail.put("lastFailureAt", snapshot.lastFailureAt() == null ? null
                : snapshot.lastFailureAt().toString());
        String key = PEER_PREFIX + face;
        alerts.raise(tenantId, key, OpsAlertRepository.Kind.PEER_DOWN,
                OpsAlertRepository.Severity.CRITICAL,
                label + " failed " + snapshot.consecutiveFailures()
                        + " consecutive exchange(s)", detail);
        open.add(key);
    }

    private void errorRate(long tenantId, OpsDashboardRepository.Totals totals, Set<String> open) {
        if (totals.finished() < properties.minFinished() || totals.successRate() == null) {
            return;
        }
        double failureRate = 1 - totals.successRate();
        if (failureRate < properties.errorRateThreshold()) {
            return;
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("finished", totals.finished());
        detail.put("failed", totals.failed());
        detail.put("failureRate", round(failureRate));
        detail.put("threshold", properties.errorRateThreshold());
        detail.put("windowMinutes", properties.windowMinutes());
        alerts.raise(tenantId, ERROR_RATE, OpsAlertRepository.Kind.ERROR_RATE,
                OpsAlertRepository.Severity.WARNING,
                String.format(Locale.ROOT, "%.1f%% of finished requests failed", failureRate * 100),
                detail);
        open.add(ERROR_RATE);
    }

    private void latency(long tenantId, OpsDashboardRepository.Totals totals, Set<String> open) {
        int slo = ops.sloP95Ms();
        if (slo <= 0 || totals.answered() < properties.minFinished()
                || totals.p95ResponseMs() == null || totals.p95ResponseMs() <= slo) {
            return;
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("p95ResponseMs", round(totals.p95ResponseMs()));
        detail.put("sloP95Ms", slo);
        detail.put("answered", totals.answered());
        alerts.raise(tenantId, LATENCY, OpsAlertRepository.Kind.LATENCY_SLO,
                OpsAlertRepository.Severity.WARNING,
                "p95 response time " + Math.round(totals.p95ResponseMs())
                        + " ms is above the " + slo + " ms objective", detail);
        open.add(LATENCY);
    }

    private boolean configured(String face) {
        return switch (face) {
            case PeerHealth.DIAMETER -> diameter.configured();
            case PeerHealth.M3UA -> m3ua.configured();
            default -> false;
        };
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
