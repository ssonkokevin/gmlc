package com.hamiltel.gmlc.webhook;

import com.hamiltel.gmlc.audit.AuditTrail;
import com.hamiltel.gmlc.geofence.GeofenceRepository;
import com.hamiltel.gmlc.ops.OpsBadRequestException;
import com.hamiltel.gmlc.ops.OpsConflictException;
import com.hamiltel.gmlc.ops.OpsNotFoundException;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Webhook subscription administration. The signing secret is generated here,
 * returned once to the operator who created the subscription and never again;
 * a read endpoint reports that a secret is set without showing it.
 *
 * <p>A subscription sends only the events it selects. It receives the masked
 * subject label and the pseudonymous reference, never the MSISDN or IMSI.
 */
@Service
public class WebhookService {

    static final Set<String> EVENT_TYPES = Set.of("ENTER", "LEAVE", "INSIDE");
    static final int MAX_SUBSCRIPTIONS = 50;

    private final EventSubscriptionRepository subscriptions;
    private final GeofenceRepository geofences;
    private final AuditTrail audit;
    private final SecureRandom random = new SecureRandom();

    public WebhookService(EventSubscriptionRepository subscriptions,
                          GeofenceRepository geofences, AuditTrail audit) {
        this.subscriptions = subscriptions;
        this.geofences = geofences;
        this.audit = audit;
    }

    public List<EventSubscriptionRepository.SubscriptionView> list(long tenantId) {
        return subscriptions.list(tenantId);
    }

    public EventSubscriptionRepository.SubscriptionView find(long tenantId, UUID id) {
        return subscriptions.find(tenantId, id)
                .orElseThrow(() -> new OpsNotFoundException("subscription"));
    }

    public Created create(long tenantId, UUID actorUserId, String name, String url,
                          List<String> eventTypes, UUID geofenceId, boolean enabled) {
        String cleanName = requiredName(name);
        String cleanUrl = webhookUrl(url);
        List<String> types = cleanEventTypes(eventTypes);
        if (geofenceId != null) {
            geofences.find(tenantId, geofenceId)
                    .orElseThrow(() -> new OpsNotFoundException("geofence"));
        }
        if (subscriptions.existsName(tenantId, cleanName, null)) {
            throw new OpsConflictException("SUBSCRIPTION_NAME_TAKEN",
                    "a subscription with that name already exists");
        }
        if (subscriptions.list(tenantId).size() >= MAX_SUBSCRIPTIONS) {
            throw new OpsBadRequestException("SUBSCRIPTION_LIMIT_REACHED",
                    "this tenant already has " + MAX_SUBSCRIPTIONS + " subscriptions");
        }

        UUID subscriptionId = UUID.randomUUID();
        String secret = newSecret();
        subscriptions.insert(tenantId, subscriptionId, cleanName, cleanUrl, secret, types,
                geofenceId, enabled, actorUserId);
        audit(tenantId, actorUserId, "WEBHOOK_SUBSCRIPTION_CREATED", subscriptionId, cleanUrl,
                Map.of("eventTypes", types, "geofenceId", geofenceId == null ? "all"
                        : geofenceId.toString(), "enabled", enabled));
        return new Created(find(tenantId, subscriptionId), secret);
    }

    public EventSubscriptionRepository.SubscriptionView update(
            long tenantId, UUID actorUserId, UUID subscriptionId, Patch patch) {
        EventSubscriptionRepository.SubscriptionView existing = find(tenantId, subscriptionId);
        String name = patch.name() == null ? existing.name() : requiredName(patch.name());
        if (!name.equals(existing.name())
                && subscriptions.existsName(tenantId, name, subscriptionId)) {
            throw new OpsConflictException("SUBSCRIPTION_NAME_TAKEN",
                    "a subscription with that name already exists");
        }
        String url = patch.url() == null ? existing.url() : webhookUrl(patch.url());
        List<String> types = patch.eventTypes() == null ? existing.eventTypes()
                : cleanEventTypes(patch.eventTypes());
        UUID geofenceId = patch.geofenceId() == null ? existing.geofenceId()
                : patch.geofenceId();
        if (geofenceId != null && !geofenceId.equals(existing.geofenceId())) {
            geofences.find(tenantId, geofenceId)
                    .orElseThrow(() -> new OpsNotFoundException("geofence"));
        }
        boolean enabled = patch.enabled() == null ? existing.enabled() : patch.enabled();
        subscriptions.update(tenantId, subscriptionId, name, url, types, geofenceId, enabled);
        audit(tenantId, actorUserId, "WEBHOOK_SUBSCRIPTION_UPDATED", subscriptionId, url,
                Map.of("name", name, "enabled", enabled));
        return find(tenantId, subscriptionId);
    }

    public void delete(long tenantId, UUID actorUserId, UUID subscriptionId) {
        EventSubscriptionRepository.SubscriptionView existing = find(tenantId, subscriptionId);
        subscriptions.delete(tenantId, subscriptionId);
        audit(tenantId, actorUserId, "WEBHOOK_SUBSCRIPTION_DELETED", subscriptionId,
                existing.url(), Map.of("name", existing.name()));
    }

    public List<EventSubscriptionRepository.DeliveryView> deliveries(long tenantId,
                                                                     UUID subscriptionId,
                                                                     int limit) {
        find(tenantId, subscriptionId);
        return subscriptions.deliveries(tenantId, subscriptionId, limit);
    }

    /** Enqueues a stored event for every enabled subscription that selects it. */
    public void enqueue(long tenantId, long eventId) {
        subscriptions.enqueue(tenantId, eventId);
    }

    private void audit(long tenantId, UUID actorUserId, String action, UUID subscriptionId,
                       String url, Map<String, Object> detail) {
        Map<String, Object> row = new LinkedHashMap<>(detail);
        row.put("subscriptionId", subscriptionId.toString());
        row.put("host", host(url));
        audit.recordOperator(tenantId, actorUserId, action, AuditTrail.Outcome.ALLOWED, row);
    }

    private static String requiredName(String name) {
        if (name == null || name.isBlank()) {
            throw new OpsBadRequestException("SUBSCRIPTION_NAME_REQUIRED",
                    "a subscription name is required");
        }
        return name.trim();
    }

    private static String webhookUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new OpsBadRequestException("WEBHOOK_URL_REQUIRED", "a webhook URL is required");
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            throw new OpsBadRequestException("WEBHOOK_URL_INVALID",
                    "the webhook URL is not a valid URI");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new OpsBadRequestException("WEBHOOK_URL_INVALID",
                    "the webhook URL must use http or https");
        }
        if (uri.getHost() == null || uri.getUserInfo() != null) {
            throw new OpsBadRequestException("WEBHOOK_URL_INVALID",
                    "the webhook URL must name a host and carry no credentials");
        }
        return uri.toString();
    }

    private static List<String> cleanEventTypes(List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            throw new OpsBadRequestException("WEBHOOK_EVENT_TYPES_REQUIRED",
                    "at least one event type is required");
        }
        Set<String> types = new LinkedHashSet<>();
        for (String type : requested) {
            String clean = type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
            if (!EVENT_TYPES.contains(clean)) {
                throw new OpsBadRequestException("WEBHOOK_EVENT_TYPE_NOT_SUPPORTED",
                        "event types must be ENTER, LEAVE or INSIDE");
            }
            types.add(clean);
        }
        return List.copyOf(types);
    }

    private String newSecret() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static String host(String url) {
        try {
            return URI.create(url).getHost();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public record Patch(String name, String url, List<String> eventTypes, UUID geofenceId,
                        Boolean enabled) {
    }

    public record Created(EventSubscriptionRepository.SubscriptionView subscription, String secret) {
    }
}
