package com.hamiltel.gmlc.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Outbound webhook delivery. A delivery is retried with a widening backoff and
 * then marked FAILED; the receiving side must treat a repeated delivery id as
 * the same event.
 *
 * @param enabled        run the periodic flush at all
 * @param flushSeconds   seconds between flushes
 * @param initialDelaySeconds seconds before the first flush after start-up
 * @param timeoutSeconds per-request timeout
 * @param maxAttempts    attempts before a delivery is marked FAILED
 * @param batchSize      deliveries inspected per flush
 */
@ConfigurationProperties(prefix = "gmlc.webhooks")
public record WebhookProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("10") long flushSeconds,
        @DefaultValue("15") long initialDelaySeconds,
        @DefaultValue("5") long timeoutSeconds,
        @DefaultValue("5") int maxAttempts,
        @DefaultValue("50") int batchSize) {

    public WebhookProperties {
        if (flushSeconds <= 0) {
            throw new IllegalArgumentException("gmlc.webhooks.flush-seconds must be positive");
        }
        if (initialDelaySeconds < 0) {
            throw new IllegalArgumentException(
                    "gmlc.webhooks.initial-delay-seconds cannot be negative");
        }
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("gmlc.webhooks.timeout-seconds must be positive");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("gmlc.webhooks.max-attempts must be at least 1");
        }
        if (batchSize < 1) {
            throw new IllegalArgumentException("gmlc.webhooks.batch-size must be at least 1");
        }
    }
}
