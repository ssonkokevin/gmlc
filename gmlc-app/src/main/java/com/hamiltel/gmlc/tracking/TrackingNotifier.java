package com.hamiltel.gmlc.tracking;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamiltel.gmlc.config.TrackingProperties;
import com.hamiltel.gmlc.webhook.WebhookSignature;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sends a tracking notification. Only a signed webhook is built; the other
 * channel types are refused at creation, so no credential is ever stored for a
 * notifier this deployment cannot deliver through.
 *
 * <p>One attempt per run: a failure is recorded on the run and the next
 * schedule tick is the retry. The body never carries the MSISDN or IMSI, and
 * the signature covers the timestamp and the exact bytes sent, using the same
 * headers as geofence webhooks.
 */
@Component
public class TrackingNotifier {

    static final String EVENT_HEADER = "X-GMLC-Event";
    static final String DELIVERY_HEADER = "X-GMLC-Delivery";
    static final String EVENT_TYPE = "tracking.fix";

    private static final int MAX_DETAIL = 200;

    private final ObjectMapper mapper;
    private final SecretBox secrets;
    private final TrackingProperties properties;
    private final HttpClient http;

    public TrackingNotifier(ObjectMapper mapper, SecretBox secrets, TrackingProperties properties) {
        this.mapper = mapper;
        this.secrets = secrets;
        this.properties = properties;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.timeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * @return the outcome to record on the run; {@code state} is SENT, FAILED
     * or NOT_CONFIGURED and {@code detail} never carries the secret or URL
     */
    public Delivery send(NotificationChannelRepository.DeliveryChannel channel,
                         String deliveryId, Map<String, Object> event) {
        if (!"WEBHOOK".equals(channel.type())) {
            return new Delivery("NOT_CONFIGURED",
                    "no notifier is built for " + channel.type());
        }
        if (!channel.enabled()) {
            return new Delivery("FAILED", "channel is disabled");
        }
        String secret;
        try {
            secret = secrets.open(channel.sealedSecret());
        } catch (IllegalStateException e) {
            return new Delivery("NOT_CONFIGURED",
                    "the signing secret could not be read with this deployment's key");
        }
        String body;
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("deliveryId", deliveryId);
            payload.put("event", event);
            body = mapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            return new Delivery("FAILED", "payload could not be serialised");
        }
        long timestamp = Instant.now().getEpochSecond();
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(channel.url()))
                    .timeout(Duration.ofSeconds(properties.timeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "GMLC-Notifier/1")
                    .header(EVENT_HEADER, EVENT_TYPE)
                    .header(DELIVERY_HEADER, deliveryId)
                    .header(WebhookSignature.TIMESTAMP_HEADER, Long.toString(timestamp))
                    .header(WebhookSignature.SIGNATURE_HEADER,
                            WebhookSignature.sign(secret, timestamp, body))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
        } catch (IllegalArgumentException e) {
            return new Delivery("FAILED", "stored URL is not a valid URI");
        }
        try {
            HttpResponse<Void> response = http.send(request,
                    HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return new Delivery("SENT", "HTTP " + response.statusCode());
            }
            return new Delivery("FAILED", "HTTP " + response.statusCode());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Delivery("FAILED", "interrupted");
        } catch (java.io.IOException | RuntimeException e) {
            return new Delivery("FAILED", truncate(e.getClass().getSimpleName()));
        }
    }

    private static String truncate(String detail) {
        return detail.length() <= MAX_DETAIL ? detail : detail.substring(0, MAX_DETAIL);
    }

    public record Delivery(String state, String detail) {
    }
}
