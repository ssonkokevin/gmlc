package com.hamiltel.gmlc.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.hamiltel.gmlc.geofence.GeofenceGeometry;
import com.hamiltel.gmlc.geofence.GeofenceRepository;
import com.hamiltel.gmlc.geofence.GeofenceService;
import com.hamiltel.gmlc.geofence.GeofenceShapeException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Geofences and their events in the console.
 *
 * <p>A definition may be read with VIEW_DASHBOARD, but the event feed contains
 * positions and therefore needs VIEW_REQUESTS, the permission that already
 * governs location data. Every change needs MANAGE_GEOFENCES. Arming a subject
 * additionally requires a live consent for the chosen client and purpose, and
 * a refusal is audited like any other privacy decision.
 */
@RestController
@RequestMapping("/v1/ops/geofences")
public class OpsGeofenceController {

    private static final int MAX_LIMIT = 200;
    private static final int DEFAULT_LIMIT = 50;

    private final GeofenceRepository geofences;
    private final GeofenceService service;

    public OpsGeofenceController(GeofenceRepository geofences, GeofenceService service) {
        this.geofences = geofences;
        this.service = service;
    }

    @GetMapping
    @OpsRequires(OpsPermission.VIEW_DASHBOARD)
    public List<GeofenceRepository.GeofenceView> list(@OpsCurrentSession OpsContext context) {
        return geofences.list(context.tenantId());
    }

    @GetMapping("/summary")
    @OpsRequires(OpsPermission.VIEW_DASHBOARD)
    public GeofenceSummary summary(@OpsCurrentSession OpsContext context,
                                   @RequestParam(defaultValue = "24h") String range) {
        OpsRange window = OpsRange.parse(range, null);
        GeofenceRepository.Summary summary =
                geofences.summary(context.tenantId(), window.from(), window.to());
        return new GeofenceSummary(window.key(), window.from(), window.to(), summary,
                reportingCapabilities());
    }

    @GetMapping("/arms")
    @OpsRequires(OpsPermission.VIEW_DASHBOARD)
    public List<GeofenceRepository.ArmView> arms(@OpsCurrentSession OpsContext context,
                                                 @RequestParam(defaultValue = "INSIDE") String state,
                                                 @RequestParam(defaultValue = "100") int limit) {
        String outcome = state == null ? "INSIDE" : state.trim().toUpperCase(java.util.Locale.ROOT);
        if (!"INSIDE".equals(outcome) && !"OUTSIDE".equals(outcome)) {
            throw new OpsBadRequestException("GEOFENCE_STATE_NOT_SUPPORTED",
                    "state must be INSIDE or OUTSIDE");
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new OpsBadRequestException("LIMIT_OUT_OF_RANGE",
                    "limit must be between 1 and " + MAX_LIMIT);
        }
        return geofences.armsByState(context.tenantId(), outcome, limit);
    }

    @GetMapping("/events")
    @OpsRequires(OpsPermission.VIEW_REQUESTS)
    public EventPage events(@OpsCurrentSession OpsContext context,
                            @RequestParam(defaultValue = "24h") String range,
                            @RequestParam(required = false) String type,
                            @RequestParam(required = false) UUID geofenceId,
                            @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit,
                            @RequestParam(defaultValue = "0") int offset) {
        OpsRange window = OpsRange.parse(range, null);
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new OpsBadRequestException("LIMIT_OUT_OF_RANGE",
                    "limit must be between 1 and " + MAX_LIMIT);
        }
        if (offset < 0) {
            throw new OpsBadRequestException("OFFSET_OUT_OF_RANGE", "offset must be 0 or more");
        }
        String eventType = type == null || type.isBlank()
                ? null : type.trim().toUpperCase(java.util.Locale.ROOT);
        if (eventType != null && !List.of("ENTER", "LEAVE", "INSIDE").contains(eventType)) {
            throw new OpsBadRequestException("GEOFENCE_EVENT_NOT_SUPPORTED",
                    "type must be ENTER, LEAVE or INSIDE");
        }
        long total = geofences.eventCount(context.tenantId(), window.from(), window.to(),
                eventType, geofenceId);
        List<GeofenceRepository.EventRow> items = geofences.events(context.tenantId(),
                window.from(), window.to(), eventType, geofenceId, limit, offset);
        return new EventPage(window.key(), window.from(), window.to(), eventType, geofenceId,
                limit, offset, total, items);
    }

    @GetMapping("/{geofenceId}")
    @OpsRequires(OpsPermission.VIEW_DASHBOARD)
    public GeofenceRepository.GeofenceView get(@OpsCurrentSession OpsContext context,
                                               @PathVariable UUID geofenceId) {
        return service.find(context.tenantId(), geofenceId);
    }

    @GetMapping("/{geofenceId}/arms")
    @OpsRequires(OpsPermission.VIEW_DASHBOARD)
    public List<GeofenceRepository.ArmView> geofenceArms(@OpsCurrentSession OpsContext context,
                                                         @PathVariable UUID geofenceId) {
        service.find(context.tenantId(), geofenceId);
        return geofences.arms(context.tenantId(), geofenceId);
    }

    @PostMapping
    @OpsRequires(OpsPermission.MANAGE_GEOFENCES)
    public ResponseEntity<GeofenceRepository.GeofenceView> create(
            @OpsCurrentSession OpsContext context, @Valid @RequestBody CreateRequest body) {
        GeofenceGeometry geometry = geometry(body.geometry());
        GeofenceRepository.GeofenceView created = service.create(context.tenantId(),
                context.user().userId(), geometry, Boolean.TRUE.equals(body.reportInside()),
                body.name(), body.description(), body.group());
        return ResponseEntity.status(HttpStatus.CREATED)
                .header("Location", "/v1/ops/geofences/" + created.geofenceId())
                .body(created);
    }

    @PatchMapping("/{geofenceId}")
    @OpsRequires(OpsPermission.MANAGE_GEOFENCES)
    public GeofenceRepository.GeofenceView update(@OpsCurrentSession OpsContext context,
                                                  @PathVariable UUID geofenceId,
                                                  @Valid @RequestBody PatchRequest body) {
        return service.update(context.tenantId(), context.user().userId(), geofenceId,
                new GeofenceService.Patch(body.name(), body.description(), body.group(),
                        body.reportInside(), body.enabled()));
    }

    @PostMapping("/{geofenceId}/arm")
    @OpsRequires(OpsPermission.MANAGE_GEOFENCES)
    public ResponseEntity<ArmResponse> arm(@OpsCurrentSession OpsContext context,
                                           @PathVariable UUID geofenceId,
                                           @Valid @RequestBody ArmRequest body) {
        GeofenceService.ArmResult result = service.arm(context.tenantId(),
                context.user().userId(), geofenceId,
                new GeofenceService.Arm(body.clientId(), body.msisdn(), body.imsi(),
                        body.purpose()));
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status)
                .body(new ArmResponse(result.created(), geofenceId, result.arm()));
    }

    @DeleteMapping("/{geofenceId}/arms/{subjectRef}")
    @OpsRequires(OpsPermission.MANAGE_GEOFENCES)
    public ResponseEntity<Void> disarm(@OpsCurrentSession OpsContext context,
                                       @PathVariable UUID geofenceId,
                                       @PathVariable String subjectRef) {
        service.disarm(context.tenantId(), context.user().userId(), geofenceId, subjectRef);
        return ResponseEntity.noContent().build();
    }

    private static GeofenceGeometry geometry(JsonNode node) {
        try {
            return GeofenceGeometry.parse(node);
        } catch (GeofenceShapeException e) {
            throw new OpsBadRequestException(e.code(), e.getMessage());
        }
    }

    private static Map<String, Feature> reportingCapabilities() {
        Map<String, Feature> features = new LinkedHashMap<>();
        features.put("areaEventsFromStoredResults", new Feature(true,
                "ENTER and LEAVE are derived from location results the gateway already stored"));
        features.put("networkAreaEvents", new Feature(false,
                "no MAP SLR or SLg LRR event-reporting subscription is built;"
                        + " events come only from stored results"));
        features.put("periodicLocation", new Feature(false,
                "no deferred location (LDR) subscription path is built, so the gateway cannot"
                        + " ask the network to report periodically"));
        features.put("motionEvents", new Feature(false,
                "no motion event reporting is built in this deployment"));
        features.put("ueAvailable", new Feature(false,
                "no UE-availability notification path is built in this deployment"));
        features.put("eventWebhooks", new Feature(false,
                "no outbound webhook subscription is built in this deployment"));
        return features;
    }

    // ---------------------------------------------------------------- records

    public record CreateRequest(
            @NotBlank @Size(max = 128) String name,
            @Size(max = 512) String description,
            @Size(max = 64) String group,
            Boolean reportInside,
            @NotNull JsonNode geometry) {
    }

    public record PatchRequest(
            @Size(max = 128) String name,
            @Size(max = 512) String description,
            @Size(max = 64) String group,
            Boolean reportInside,
            Boolean enabled) {
    }

    public record ArmRequest(
            @NotNull UUID clientId,
            @Pattern(regexp = "[0-9]{7,15}", message = "must be 7-15 E.164 digits")
            String msisdn,
            @Pattern(regexp = "[0-9]{14,15}", message = "must be a 14 or 15 digit IMSI")
            String imsi,
            @NotBlank
            @Pattern(regexp = "[A-Z_]{3,32}", message = "must be an uppercase purpose code")
            String purpose) {
    }

    public record ArmResponse(boolean created, UUID geofenceId,
                              GeofenceRepository.ArmView arm) {
    }

    public record EventPage(String range, Instant from, Instant to, String type,
                            UUID geofenceId, int limit, int offset, long total,
                            List<GeofenceRepository.EventRow> items) {
    }

    public record GeofenceSummary(String range, Instant from, Instant to,
                                  GeofenceRepository.Summary counts,
                                  Map<String, Feature> reporting) {
    }

    public record Feature(boolean available, String reason) {
    }
}
