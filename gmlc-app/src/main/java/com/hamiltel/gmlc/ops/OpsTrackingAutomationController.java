package com.hamiltel.gmlc.ops;

import com.hamiltel.gmlc.tracking.NotificationChannelRepository;
import com.hamiltel.gmlc.tracking.NotificationChannelService;
import com.hamiltel.gmlc.tracking.TrackingScheduleRepository;
import com.hamiltel.gmlc.tracking.TrackingScheduleService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
import java.util.List;
import java.util.UUID;

/**
 * Automated tracking schedules and the channels they notify.
 *
 * <p>A schedule decides when the gateway places governed requests for a
 * tracked subject, so creating, pausing, deleting or running one needs LOCATE.
 * Reading the runs needs VIEW_REQUESTS because they name requests and their
 * outcomes. A channel stores credentials, so changing one needs
 * MANAGE_NOTIFIERS; reading one never returns the secret.
 */
@RestController
@RequestMapping("/v1/ops/tracking")
public class OpsTrackingAutomationController {

    private static final int MAX_LIMIT = 200;
    private static final int DEFAULT_LIMIT = 50;

    private final TrackingScheduleService schedules;
    private final NotificationChannelService channels;

    public OpsTrackingAutomationController(TrackingScheduleService schedules,
                                           NotificationChannelService channels) {
        this.schedules = schedules;
        this.channels = channels;
    }

    // ------------------------------------------------------------- schedules

    @GetMapping("/schedules")
    @OpsRequires(OpsPermission.VIEW_REQUESTS)
    public List<TrackingScheduleRepository.ScheduleView> listSchedules(
            @OpsCurrentSession OpsContext context) {
        return schedules.list(context.tenantId());
    }

    @GetMapping("/schedules/{scheduleId}")
    @OpsRequires(OpsPermission.VIEW_REQUESTS)
    public TrackingScheduleRepository.ScheduleView getSchedule(
            @OpsCurrentSession OpsContext context, @PathVariable UUID scheduleId) {
        return schedules.find(context.tenantId(), scheduleId);
    }

    @GetMapping("/schedules/{scheduleId}/runs")
    @OpsRequires(OpsPermission.VIEW_REQUESTS)
    public List<TrackingScheduleRepository.RunView> runs(
            @OpsCurrentSession OpsContext context, @PathVariable UUID scheduleId,
            @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new OpsBadRequestException("LIMIT_OUT_OF_RANGE",
                    "limit must be between 1 and " + MAX_LIMIT);
        }
        return schedules.runs(context.tenantId(), scheduleId, limit);
    }

    @PostMapping("/schedules")
    @OpsRequires(OpsPermission.LOCATE)
    public ResponseEntity<TrackingScheduleRepository.ScheduleView> createSchedule(
            @OpsCurrentSession OpsContext context, @Valid @RequestBody CreateSchedule body) {
        TrackingScheduleRepository.ScheduleView created = schedules.create(context.tenantId(),
                context.user().userId(),
                new TrackingScheduleService.Create(body.trackedId(), body.intervalSeconds(),
                        body.startsAt(), body.endsAt(), body.accuracyMeters(),
                        body.notifyOnFix(), body.channelId()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .header("Location", "/v1/ops/tracking/schedules/" + created.scheduleId())
                .body(created);
    }

    @PatchMapping("/schedules/{scheduleId}")
    @OpsRequires(OpsPermission.LOCATE)
    public TrackingScheduleRepository.ScheduleView patchSchedule(
            @OpsCurrentSession OpsContext context, @PathVariable UUID scheduleId,
            @Valid @RequestBody PatchSchedule body) {
        return schedules.setEnabled(context.tenantId(), context.user().userId(), scheduleId,
                body.enabled());
    }

    @DeleteMapping("/schedules/{scheduleId}")
    @OpsRequires(OpsPermission.LOCATE)
    public ResponseEntity<Void> deleteSchedule(@OpsCurrentSession OpsContext context,
                                               @PathVariable UUID scheduleId) {
        schedules.delete(context.tenantId(), context.user().userId(), scheduleId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/schedules/{scheduleId}/run")
    @OpsRequires(OpsPermission.LOCATE)
    public TrackingScheduleRepository.RunView runNow(@OpsCurrentSession OpsContext context,
                                                     @PathVariable UUID scheduleId) {
        return schedules.runNow(context.tenantId(), context.user().userId(), scheduleId);
    }

    // -------------------------------------------------------------- channels

    @GetMapping("/channels")
    @OpsRequires(OpsPermission.VIEW_REQUESTS)
    public List<NotificationChannelRepository.ChannelView> listChannels(
            @OpsCurrentSession OpsContext context) {
        return channels.list(context.tenantId());
    }

    @GetMapping("/channels/{channelId}")
    @OpsRequires(OpsPermission.VIEW_REQUESTS)
    public NotificationChannelRepository.ChannelView getChannel(
            @OpsCurrentSession OpsContext context, @PathVariable UUID channelId) {
        return channels.find(context.tenantId(), channelId);
    }

    @PostMapping("/channels")
    @OpsRequires(OpsPermission.MANAGE_NOTIFIERS)
    public ResponseEntity<NotificationChannelService.Created> createChannel(
            @OpsCurrentSession OpsContext context, @Valid @RequestBody CreateChannel body) {
        NotificationChannelService.Created created = channels.create(context.tenantId(),
                context.user().userId(),
                new NotificationChannelService.Command(body.name(), body.type(), body.url(),
                        body.enabled()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .header("Location", "/v1/ops/tracking/channels/"
                        + created.channel().channelId())
                .body(created);
    }

    @PatchMapping("/channels/{channelId}")
    @OpsRequires(OpsPermission.MANAGE_NOTIFIERS)
    public NotificationChannelRepository.ChannelView patchChannel(
            @OpsCurrentSession OpsContext context, @PathVariable UUID channelId,
            @Valid @RequestBody PatchChannel body) {
        return channels.setEnabled(context.tenantId(), context.user().userId(), channelId,
                body.enabled());
    }

    @DeleteMapping("/channels/{channelId}")
    @OpsRequires(OpsPermission.MANAGE_NOTIFIERS)
    public ResponseEntity<Void> deleteChannel(@OpsCurrentSession OpsContext context,
                                              @PathVariable UUID channelId) {
        channels.delete(context.tenantId(), context.user().userId(), channelId);
        return ResponseEntity.noContent().build();
    }

    // --------------------------------------------------------------- records

    public record CreateSchedule(
            @NotNull UUID trackedId,
            @NotNull @Min(1) Integer intervalSeconds,
            Instant startsAt,
            Instant endsAt,
            @Min(1) @Max(100_000) Integer accuracyMeters,
            Boolean notifyOnFix,
            UUID channelId) {
    }

    public record PatchSchedule(@NotNull Boolean enabled) {
    }

    public record CreateChannel(
            @NotBlank @Size(max = 128) String name,
            @NotBlank @Size(max = 16) String type,
            @NotBlank @Size(max = 1024) String url,
            Boolean enabled) {
    }

    public record PatchChannel(@NotNull Boolean enabled) {
    }
}
