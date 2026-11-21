package com.hamiltel.gmlc.ops;

import com.hamiltel.gmlc.webhook.EventSubscriptionRepository;
import com.hamiltel.gmlc.webhook.WebhookService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Outbound webhook subscriptions for geofence events.
 *
 * <p>Creating a subscription decides where positions leave the gateway, so it
 * needs MANAGE_GEOFENCES, the permission that already governs area monitoring.
 * The signing secret is returned once at creation and never by a read. The
 * delivery log needs VIEW_REQUESTS because it is evidence about location data.
 */
@RestController
@RequestMapping("/v1/ops/subscriptions")
public class OpsSubscriptionController {

    private static final int MAX_LIMIT = 200;
    private static final int DEFAULT_LIMIT = 50;

    private final WebhookService service;

    public OpsSubscriptionController(WebhookService service) {
        this.service = service;
    }

    @GetMapping
    @OpsRequires(OpsPermission.VIEW_DASHBOARD)
    public List<EventSubscriptionRepository.SubscriptionView> list(
            @OpsCurrentSession OpsContext context) {
        return service.list(context.tenantId());
    }

    @GetMapping("/{subscriptionId}")
    @OpsRequires(OpsPermission.VIEW_DASHBOARD)
    public EventSubscriptionRepository.SubscriptionView get(@OpsCurrentSession OpsContext context,
                                                             @PathVariable UUID subscriptionId) {
        return service.find(context.tenantId(), subscriptionId);
    }

    @GetMapping("/{subscriptionId}/deliveries")
    @OpsRequires(OpsPermission.VIEW_REQUESTS)
    public List<EventSubscriptionRepository.DeliveryView> deliveries(
            @OpsCurrentSession OpsContext context, @PathVariable UUID subscriptionId,
            @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new OpsBadRequestException("LIMIT_OUT_OF_RANGE",
                    "limit must be between 1 and " + MAX_LIMIT);
        }
        return service.deliveries(context.tenantId(), subscriptionId, limit);
    }

    @PostMapping
    @OpsRequires(OpsPermission.MANAGE_GEOFENCES)
    public ResponseEntity<CreatedResponse> create(@OpsCurrentSession OpsContext context,
                                                  @Valid @RequestBody CreateRequest body) {
        WebhookService.Created created = service.create(context.tenantId(),
                context.user().userId(), body.name(), body.url(), body.eventTypes(),
                body.geofenceId(), !Boolean.FALSE.equals(body.enabled()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .header("Location", "/v1/ops/subscriptions/"
                        + created.subscription().subscriptionId())
                .body(new CreatedResponse(created.subscription(), created.secret(),
                        "Store this signing secret now: it is not shown again."));
    }

    @PatchMapping("/{subscriptionId}")
    @OpsRequires(OpsPermission.MANAGE_GEOFENCES)
    public EventSubscriptionRepository.SubscriptionView update(
            @OpsCurrentSession OpsContext context, @PathVariable UUID subscriptionId,
            @Valid @RequestBody PatchRequest body) {
        return service.update(context.tenantId(), context.user().userId(), subscriptionId,
                new WebhookService.Patch(body.name(), body.url(), body.eventTypes(),
                        body.geofenceId(), body.enabled()));
    }

    @DeleteMapping("/{subscriptionId}")
    @OpsRequires(OpsPermission.MANAGE_GEOFENCES)
    public ResponseEntity<Void> delete(@OpsCurrentSession OpsContext context,
                                       @PathVariable UUID subscriptionId) {
        service.delete(context.tenantId(), context.user().userId(), subscriptionId);
        return ResponseEntity.noContent().build();
    }

    public record CreateRequest(
            @NotBlank @Size(max = 128) String name,
            @NotBlank @Size(max = 1024) String url,
            @NotEmpty @Size(max = 3) List<String> eventTypes,
            UUID geofenceId,
            Boolean enabled) {
    }

    public record PatchRequest(
            @Size(max = 128) String name,
            @Size(max = 1024) String url,
            @Size(max = 3) List<String> eventTypes,
            UUID geofenceId,
            Boolean enabled) {
    }

    public record CreatedResponse(EventSubscriptionRepository.SubscriptionView subscription,
                                  String secret, String secretNotice) {
    }
}
