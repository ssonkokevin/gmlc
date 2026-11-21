package com.hamiltel.gmlc.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The alert thresholds. They are deployment settings with a stated default;
 * changing one is a configuration change, not a code change, and the values in
 * force are visible on the console.
 *
 * @param enabled            run the periodic evaluation at all
 * @param evalSeconds        seconds between evaluations
 * @param windowMinutes      how far back each evaluation looks
 * @param minFinished        finished requests needed before a rate is judged
 * @param errorRateThreshold failed share of finished work that raises an alert
 * @param peerFailureThreshold consecutive transport failures that raise one
 * @param cellGapThreshold   cell-plan misses in the window that raise one
 */
@ConfigurationProperties(prefix = "gmlc.ops.alerts")
public record OpsAlertProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("60") long evalSeconds,
        @DefaultValue("15") long windowMinutes,
        @DefaultValue("10") long minFinished,
        @DefaultValue("0.25") double errorRateThreshold,
        @DefaultValue("3") int peerFailureThreshold,
        @DefaultValue("1") long cellGapThreshold) {

    public OpsAlertProperties {
        if (evalSeconds <= 0) {
            throw new IllegalArgumentException("gmlc.ops.alerts.eval-seconds must be positive");
        }
        if (windowMinutes <= 0) {
            throw new IllegalArgumentException("gmlc.ops.alerts.window-minutes must be positive");
        }
        if (minFinished < 0) {
            throw new IllegalArgumentException("gmlc.ops.alerts.min-finished cannot be negative");
        }
        if (errorRateThreshold <= 0 || errorRateThreshold > 1) {
            throw new IllegalArgumentException(
                    "gmlc.ops.alerts.error-rate-threshold must be in (0, 1]");
        }
        if (peerFailureThreshold < 1) {
            throw new IllegalArgumentException(
                    "gmlc.ops.alerts.peer-failure-threshold must be at least 1");
        }
        if (cellGapThreshold < 1) {
            throw new IllegalArgumentException(
                    "gmlc.ops.alerts.cell-gap-threshold must be at least 1");
        }
    }
}
