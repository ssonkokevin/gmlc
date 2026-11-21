package com.hamiltel.gmlc.ops;

import com.hamiltel.gmlc.config.OpsProperties;
import com.hamiltel.gmlc.tracking.TrackingRepository;
import com.hamiltel.gmlc.tracking.TrackingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The tracking roster and the trail each subject resolves to.
 *
 * <p>Reading a subject or its trail names positions, so it needs
 * VIEW_REQUESTS. Adding, pausing or removing a subject needs LOCATE, the
 * permission that governs placing location work for a subscriber. Adding is
 * consent-checked by the service exactly like a locate.
 *
 * <p>The trail is made of stored results only. This deployment has no deferred
 * or periodic location subscription, so the console must not present a
 * tracked subject as a live feed; the summary says that in its capability map.
 */
@RestController
@RequestMapping("/v1/ops/tracking")
public class OpsTrackingController {

    private static final int MAX_LIMIT = 200;
    private static final int DEFAULT_LIMIT = 100;

    private final TrackingService service;
    private final OpsProperties properties;

    public OpsTrackingController(TrackingService service, OpsProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @GetMapping
    @OpsRequires(OpsPermission.VIEW_REQUESTS)
    public List<TrackingRepository.TrackedView> list(@OpsCurrentSession OpsContext context) {
        return service.list(context.tenantId());
    }

    @GetMapping("/summary")
    @OpsRequires(OpsPermission.VIEW_DASHBOARD)
    public Summary summary(@OpsCurrentSession OpsContext context) {
        List<TrackingRepository.TrackedView> subjects = service.list(context.tenantId());
        long enabled = subjects.stream().filter(TrackingRepository.TrackedView::enabled).count();
        long withFix = subjects.stream().filter(s -> s.lastFix() != null).count();
        long fixes = subjects.stream().mapToLong(TrackingRepository.TrackedView::fixes).sum();
        return new Summary(subjects.size(), enabled, withFix, fixes,
                properties.dataSourceLabel(), reportingCapabilities());
    }

    @GetMapping("/{trackedId}")
    @OpsRequires(OpsPermission.VIEW_REQUESTS)
    public TrackingRepository.TrackedView get(@OpsCurrentSession OpsContext context,
                                              @PathVariable UUID trackedId) {
        return service.find(context.tenantId(), trackedId);
    }

    @GetMapping("/{trackedId}/trail")
    @OpsRequires(OpsPermission.VIEW_REQUESTS)
    public List<TrackingRepository.FixView> trail(
            @OpsCurrentSession OpsContext context, @PathVariable UUID trackedId,
            @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new OpsBadRequestException("LIMIT_OUT_OF_RANGE",
                    "limit must be between 1 and " + MAX_LIMIT);
        }
        return service.trail(context.tenantId(), trackedId, limit);
    }

    @PostMapping
    @OpsRequires(OpsPermission.LOCATE)
    public ResponseEntity<TrackingRepository.TrackedView> add(
            @OpsCurrentSession OpsContext context, @Valid @RequestBody AddSubject body) {
        TrackingRepository.TrackedView created = service.register(context.tenantId(),
                context.user().userId(),
                new TrackingService.Register(body.clientId(), body.msisdn(), body.imsi(),
                        body.purpose()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .header("Location", "/v1/ops/tracking/" + created.trackedId())
                .body(created);
    }

    @PatchMapping("/{trackedId}")
    @OpsRequires(OpsPermission.LOCATE)
    public TrackingRepository.TrackedView update(@OpsCurrentSession OpsContext context,
                                                 @PathVariable UUID trackedId,
                                                 @Valid @RequestBody PatchSubject body) {
        return service.setEnabled(context.tenantId(), context.user().userId(), trackedId,
                body.enabled());
    }

    @DeleteMapping("/{trackedId}")
    @OpsRequires(OpsPermission.LOCATE)
    public ResponseEntity<Void> remove(@OpsCurrentSession OpsContext context,
                                       @PathVariable UUID trackedId) {
        service.remove(context.tenantId(), context.user().userId(), trackedId);
        return ResponseEntity.noContent().build();
    }

    private static Map<String, Feature> reportingCapabilities() {
        Map<String, Feature> features = new LinkedHashMap<>();
        features.put("trailsFromStoredResults", new Feature(true,
                "each trail point is a location result the gateway already stored"
                        + " after a governed request from this client and purpose"));
        features.put("liveNetworkTracking", new Feature(false,
                "no deferred (LDR), periodic or event-driven location subscription is built,"
                        + " so the gateway cannot ask the network for a fresh position"));
        features.put("scheduledTracking", new Feature(false,
                "automated schedules are not built in this deployment"));
        features.put("notifyOnNewFix", new Feature(false,
                "no notifier is built yet; a new fix is visible when the trail is read"));
        features.put("heatmapRendering", new Feature(true,
                "the console can render the stored fixes as a heatmap; the points are"
                        + " the same stored results, not a separate feed"));
        return features;
    }

    // ---------------------------------------------------------------- records

    public record AddSubject(
            @NotNull UUID clientId,
            @Pattern(regexp = "[0-9]{7,15}", message = "must be 7-15 E.164 digits")
            String msisdn,
            @Pattern(regexp = "[0-9]{14,15}", message = "must be a 14 or 15 digit IMSI")
            String imsi,
            @NotBlank
            @Pattern(regexp = "[A-Z_]{3,32}", message = "must be an uppercase purpose code")
            String purpose) {
    }

    public record PatchSubject(@NotNull Boolean enabled) {
    }

    public record Summary(long total, long enabled, long withFix, long fixes, String dataSource,
                          Map<String, Feature> reporting) {
    }

    public record Feature(boolean available, String reason) {
    }
}
