package com.hamiltel.gmlc.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Automated tracking and notification settings.
 *
 * @param schedulesEnabled    run the periodic dispatch at all
 * @param flushSeconds        seconds between dispatch ticks
 * @param initialDelaySeconds seconds before the first tick after start-up
 * @param timeoutSeconds      per-notification HTTP timeout
 * @param minIntervalSeconds  floor for a schedule interval; licence metering
 *                            is not wired, so this is a paging guard and a
 *                            deployment may raise it but not lower it
 * @param batchSize           schedules inspected per tick
 * @param notifierKey         base64 32-byte AES-GCM key that seals channel
 *                            secrets; blank means channels cannot be created
 */
@ConfigurationProperties(prefix = "gmlc.tracking")
public record TrackingProperties(
        @DefaultValue("true") boolean schedulesEnabled,
        @DefaultValue("30") long flushSeconds,
        @DefaultValue("45") long initialDelaySeconds,
        @DefaultValue("10") long timeoutSeconds,
        @DefaultValue("300") long minIntervalSeconds,
        @DefaultValue("20") int batchSize,
        String notifierKey) {

    private static final long HARD_FLOOR_SECONDS = 300;

    public TrackingProperties {
        if (flushSeconds <= 0) {
            throw new IllegalArgumentException("gmlc.tracking.flush-seconds must be positive");
        }
        if (initialDelaySeconds < 0) {
            throw new IllegalArgumentException(
                    "gmlc.tracking.initial-delay-seconds cannot be negative");
        }
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("gmlc.tracking.timeout-seconds must be positive");
        }
        if (minIntervalSeconds < HARD_FLOOR_SECONDS) {
            throw new IllegalArgumentException("gmlc.tracking.min-interval-seconds cannot be"
                    + " lower than " + HARD_FLOOR_SECONDS);
        }
        if (batchSize < 1) {
            throw new IllegalArgumentException("gmlc.tracking.batch-size must be at least 1");
        }
    }
}
