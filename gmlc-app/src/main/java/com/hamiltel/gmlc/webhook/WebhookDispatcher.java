package com.hamiltel.gmlc.webhook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamiltel.gmlc.config.WebhookProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Sends due webhook deliveries. Delivery is at-least-once: a receiver must
 * treat the delivery id as the idempotency key, because a timeout after the
 * remote side committed the request leaves a retry indistinguishable from the
 * first attempt. Failed attempts back off and stop after the configured cap.
 *
 * <p>The body carries the subject's pseudonymous reference and masked label,
 * never the MSISDN or IMSI. The signature covers the timestamp and the exact
 * body bytes.
 */
@Component
public class WebhookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);
    private static final long[] BACKOFF_SECONDS = {30, 120, 600, 3600};
    private static final int MAX_ERROR_LENGTH = 250;

    private final EventSubscriptionRepository subscriptions;
    private final ObjectMapper mapper;
    private final WebhookProperties properties;
    private final HttpClient http;

    public WebhookDispatcher(EventSubscriptionRepository subscriptions, ObjectMapper mapper,
                             WebhookProperties properties) {
        this.subscriptions = subscriptions;
        this.mapper = mapper;
        this.properties = properties;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.timeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Scheduled(fixedDelayString = "${gmlc.webhooks.flush-seconds:10}",
            initialDelayString = "${gmlc.webhooks.initial-delay-seconds:15}",
            timeUnit = TimeUnit.SECONDS)
    public void scheduledFlush() {
        if (!properties.enabled()) {
            return;
        }
        try {
            dispatchDue(Instant.now());
        } catch (RuntimeException e) {
            // A flush that cannot read the database retries on the next tick;
            // PENDING rows stay PENDING rather than being lost.
            log.warn("webhook flush skipped: {}", e.getClass().getSimpleName());
        }
    }

    /** Sends every delivery that is due at {@code now}; returns how many were attempted. */
    public int dispatchDue(Instant now) {
        int attempted = 0;
        for (EventSubscriptionRepository.DeliveryRow row
                : subscriptions.dueDeliveries(now, properties.batchSize())) {
            deliver(row, now);
            attempted++;
        }
        return attempted;
    }

    private void deliver(EventSubscriptionRepository.DeliveryRow row, Instant now) {
        String body;
        try {
            body = payload(row);
        } catch (JsonProcessingException e) {
            fail(row, now, null, "payload could not be serialised");
            return;
        }
        long timestamp = now.getEpochSecond();
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(row.url()))
                    .timeout(Duration.ofSeconds(properties.timeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "GMLC-Webhook/1")
                    .header("X-GMLC-Event", row.eventType())
                    .header("X-GMLC-Delivery", Long.toString(row.deliveryId()))
                    .header(WebhookSignature.TIMESTAMP_HEADER, Long.toString(timestamp))
                    .header(WebhookSignature.SIGNATURE_HEADER,
                            WebhookSignature.sign(row.secret(), timestamp, body))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
        } catch (IllegalArgumentException e) {
            fail(row, now, null, "stored URL is not a valid URI");
            return;
        }
        try {
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                subscriptions.markDelivered(row.deliveryId(), response.statusCode());
            } else {
                fail(row, now, response.statusCode(), "HTTP " + response.statusCode());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(row, now, null, "interrupted");
        } catch (java.io.IOException | RuntimeException e) {
            fail(row, now, null, e.getClass().getSimpleName());
        }
    }

    private void fail(EventSubscriptionRepository.DeliveryRow row, Instant now,
                      Integer statusCode, String error) {
        int attempt = row.attempts() + 1;
        boolean last = attempt >= properties.maxAttempts();
        Instant next = now.plusSeconds(BACKOFF_SECONDS[
                Math.min(attempt - 1, BACKOFF_SECONDS.length - 1)]);
        subscriptions.markFailure(row.deliveryId(), last, next, statusCode, truncate(error));
        if (last) {
            log.warn("webhook delivery {} for subscription {} gave up after {} attempt(s): {}",
                    row.deliveryId(), row.subscriptionId(), attempt, error);
        }
    }

    private String payload(EventSubscriptionRepository.DeliveryRow row)
            throws JsonProcessingException {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("id", row.eventId());
        event.put("type", row.eventType());
        event.put("occurredAt", row.occurredAt().toString());
        event.put("requestId", row.requestId().toString());
        event.put("resultId", row.resultId().toString());
        event.put("geofence", Map.of("id", row.geofenceId().toString(),
                "name", row.geofenceName()));
        event.put("subject", Map.of("ref", row.subjectRef(),
                "label", row.subjectLabel()));
        Map<String, Object> position = new LinkedHashMap<>();
        position.put("latitude", trim(row.latitude()));
        position.put("longitude", trim(row.longitude()));
        position.put("uncertaintyM", row.uncertaintyM());
        position.put("method", row.method());
        event.put("position", position);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("deliveryId", row.deliveryId());
        body.put("event", event);
        return mapper.writeValueAsString(body);
    }

    private static BigDecimal trim(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros();
    }

    private static String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
    }
}
