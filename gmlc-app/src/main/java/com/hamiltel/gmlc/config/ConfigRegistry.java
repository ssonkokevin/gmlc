package com.hamiltel.gmlc.config;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The configuration items an operator may change from the console.
 *
 * <p>Only items the gateway reads at run time are here. Peers, retention and
 * the licence are environment-managed and have no row: listing them as
 * editable would claim an effect the running process does not honour.
 *
 * <p>A signalling item (the LCS client name presented to an MME) needs a
 * second approver in a production deployment. That rule lives in
 * {@code ConfigService}; this registry only says which items carry it.
 */
@Component
public class ConfigRegistry {

    public enum ValueType { INTEGER, DECIMAL, TEXT }

    public record ItemSpec(String key, String label, String description, ValueType type,
                           boolean signalling, double min, double max, int maxLength,
                           Object defaultValue) {
    }

    private final Map<String, ItemSpec> specs;

    public ConfigRegistry(OpsAlertProperties alerts, OpsProperties ops, GmlcProperties gmlc) {
        Map<String, ItemSpec> map = new LinkedHashMap<>();
        add(map, new ItemSpec("alerts.windowMinutes",
                "Alert evaluation window",
                "How far back each alert evaluation looks, in minutes.",
                ValueType.INTEGER, false, 5, 1440, 0, alerts.windowMinutes()));
        add(map, new ItemSpec("alerts.minFinished",
                "Minimum finished requests",
                "Finished requests needed before a success rate or latency is judged.",
                ValueType.INTEGER, false, 0, 100_000, 0, alerts.minFinished()));
        add(map, new ItemSpec("alerts.errorRateThreshold",
                "Error-rate threshold",
                "Failed share of finished requests that raises ERROR_RATE, between 0.01 and 1.",
                ValueType.DECIMAL, false, 0.01, 1.0, 0, alerts.errorRateThreshold()));
        add(map, new ItemSpec("alerts.peerFailureThreshold",
                "Peer failure threshold",
                "Consecutive transport failures that raise PEER_DOWN.",
                ValueType.INTEGER, false, 1, 1000, 0, alerts.peerFailureThreshold()));
        add(map, new ItemSpec("alerts.cellGapThreshold",
                "Cell-plan gap threshold",
                "Cell-plan misses in the window that raise CELL_DB_GAP.",
                ValueType.INTEGER, false, 1, 100_000, 0, alerts.cellGapThreshold()));
        add(map, new ItemSpec("alerts.sloP95Ms",
                "p95 response objective",
                "Measured p95 response time may not exceed this many milliseconds; "
                        + "0 means no objective has been agreed and no LATENCY_SLO alert is raised.",
                ValueType.INTEGER, false, 0, 600_000, 0, ops.sloP95Ms()));
        add(map, new ItemSpec("lcs.clientName",
                "LCS client name",
                "The name the gateway presents to an MME as the requesting LCS client. "
                        + "It is carried on the signalling, so a production change needs a second approver.",
                ValueType.TEXT, true, 0, 0, 64,
                gmlc.lcsClientName() == null ? "GMLC" : gmlc.lcsClientName()));
        this.specs = Map.copyOf(map);
    }

    private static void add(Map<String, ItemSpec> map, ItemSpec spec) {
        map.put(spec.key(), spec);
    }

    public List<ItemSpec> specs() {
        return specs.values().stream()
                .sorted((a, b) -> a.key().compareTo(b.key()))
                .toList();
    }

    public Optional<ItemSpec> find(String key) {
        return key == null ? Optional.empty() : Optional.ofNullable(specs.get(key));
    }
}
