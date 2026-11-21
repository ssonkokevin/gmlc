package com.hamiltel.gmlc.tracking;

import com.hamiltel.gmlc.audit.AuditTrail;
import com.hamiltel.gmlc.client.ApiClient;
import com.hamiltel.gmlc.client.ApiClientRepository;
import com.hamiltel.gmlc.client.QuotaService;
import com.hamiltel.gmlc.config.TrackingProperties;
import com.hamiltel.gmlc.consent.ConsentService;
import com.hamiltel.gmlc.location.LocationRequestInput;
import com.hamiltel.gmlc.location.LocationRequestRepository;
import com.hamiltel.gmlc.location.LocationRequestService;
import com.hamiltel.gmlc.location.LocationResultRepository;
import com.hamiltel.gmlc.location.PrivacyNotSatisfiedException;
import com.hamiltel.gmlc.location.RequestRejectedException;
import com.hamiltel.gmlc.ops.OpsBadRequestException;
import com.hamiltel.gmlc.ops.OpsConflictException;
import com.hamiltel.gmlc.ops.OpsNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Automated tracking.
 *
 * <p>A schedule is a window and an interval, not a network subscription. Each
 * run places a real governed request through {@link LocationRequestService},
 * so purpose, consent, accuracy and paging quota are the same as any other
 * Le request. A refused run is recorded with the code that refused it; the
 * next run is a full interval away, so a revoked consent is not retried in a
 * tight loop.
 *
 * <p>The minimum interval is a conservative floor because licence metering is
 * not wired in this deployment. It is enforced here and by the schema.
 */
@Service
public class TrackingScheduleService {

    private static final Logger log = LoggerFactory.getLogger(TrackingScheduleService.class);

    private static final int MAX_INTERVAL_SECONDS = 86_400;
    private static final int MAX_ERROR_LENGTH = 250;

    private final TrackingScheduleRepository schedules;
    private final TrackingRepository tracking;
    private final NotificationChannelRepository channels;
    private final ApiClientRepository clients;
    private final ConsentService consents;
    private final LocationRequestService requests;
    private final QuotaService quotas;
    private final TrackingNotifier notifier;
    private final AuditTrail audit;
    private final TrackingProperties properties;

    public TrackingScheduleService(TrackingScheduleRepository schedules, TrackingRepository tracking,
                                   NotificationChannelRepository channels,
                                   ApiClientRepository clients, ConsentService consents,
                                   LocationRequestService requests, QuotaService quotas,
                                   TrackingNotifier notifier, AuditTrail audit,
                                   TrackingProperties properties) {
        this.schedules = schedules;
        this.tracking = tracking;
        this.channels = channels;
        this.clients = clients;
        this.consents = consents;
        this.requests = requests;
        this.quotas = quotas;
        this.notifier = notifier;
        this.audit = audit;
        this.properties = properties;
    }

    public TrackingScheduleRepository.ScheduleView create(long tenantId, UUID actorUserId,
                                                          Create command) {
        TrackingRepository.ScheduleTarget target = tracking
                .findTarget(tenantId, command.trackedId())
                .orElseThrow(() -> new OpsNotFoundException("tracked subject"));
        int interval = requireInterval(command.intervalSeconds());

        Instant now = Instant.now();
        Instant startsAt = command.startsAt() == null ? now : command.startsAt();
        if (command.endsAt() != null && !command.endsAt().isAfter(startsAt)) {
            throw new OpsBadRequestException("SCHEDULE_WINDOW_INVALID",
                    "endsAt must be after startsAt");
        }
        if (command.endsAt() != null && !command.endsAt().isAfter(now)) {
            throw new OpsBadRequestException("SCHEDULE_WINDOW_EMPTY",
                    "endsAt must be in the future");
        }
        if (!target.enabled()) {
            throw new OpsBadRequestException("TRACKING_DISABLED",
                    "the tracked subject is paused; enable it before scheduling");
        }
        ApiClientRepository.StoredClient client = clients
                .findByTenantAndId(tenantId, target.clientId())
                .filter(ApiClientRepository.StoredClient::enabled)
                .orElseThrow(() -> new OpsNotFoundException("client"));
        if (command.accuracyMeters() != null
                && command.accuracyMeters() < client.client().maxAccuracyM()) {
            throw new RequestRejectedException("ACCURACY_NOT_ALLOWED",
                    "this client may not ask for better than "
                            + client.client().maxAccuracyM() + " m");
        }

        ConsentService.PrivacyDecision privacy = consents.check(tenantId, target.clientId(),
                target.subjectRef(), target.purpose());
        if (!privacy.allowed()) {
            audit.recordOperator(tenantId, actorUserId, "TRACKING_SCHEDULE_ADDED",
                    AuditTrail.Outcome.DENIED,
                    Map.of("trackedId", target.trackedId().toString(),
                            "reason", "PRIVACY_NOT_SATISFIED"));
            throw new PrivacyNotSatisfiedException(target.purpose());
        }

        boolean notify = Boolean.TRUE.equals(command.notifyOnFix());
        UUID channelId = command.channelId();
        if (notify && channelId == null) {
            throw new OpsBadRequestException("NOTIFY_CHANNEL_REQUIRED",
                    "notify needs a channelId");
        }
        if (!notify && channelId != null) {
            throw new OpsBadRequestException("NOTIFY_CHANNEL_UNUSED",
                    "channelId is set but notify is false");
        }
        if (notify) {
            requireUsableChannel(tenantId, channelId);
        }

        UUID scheduleId = UUID.randomUUID();
        boolean created = schedules.insert(scheduleId, tenantId, target.trackedId(), interval,
                startsAt, command.endsAt(), command.accuracyMeters(), notify, channelId, true,
                startsAt.isAfter(now) ? startsAt : now, actorUserId);
        if (!created) {
            throw new OpsConflictException("SCHEDULE_ALREADY_EXISTS",
                    "this subject already has a schedule");
        }
        audit.recordOperator(tenantId, actorUserId, "TRACKING_SCHEDULE_ADDED",
                AuditTrail.Outcome.ALLOWED,
                Map.of("scheduleId", scheduleId.toString(),
                        "trackedId", target.trackedId().toString(),
                        "intervalSeconds", interval, "notify", notify));
        return find(tenantId, scheduleId);
    }

    public List<TrackingScheduleRepository.ScheduleView> list(long tenantId) {
        return schedules.list(tenantId);
    }

    public TrackingScheduleRepository.ScheduleView find(long tenantId, UUID scheduleId) {
        return schedules.find(tenantId, scheduleId)
                .orElseThrow(() -> new OpsNotFoundException("tracking schedule"));
    }

    public List<TrackingScheduleRepository.RunView> runs(long tenantId, UUID scheduleId,
                                                         int limit) {
        find(tenantId, scheduleId);
        return schedules.runs(tenantId, scheduleId, limit);
    }

    public TrackingScheduleRepository.ScheduleView setEnabled(long tenantId, UUID actorUserId,
                                                              UUID scheduleId, boolean enabled) {
        find(tenantId, scheduleId);
        schedules.setEnabled(tenantId, scheduleId, enabled, enabled ? Instant.now() : null);
        audit.recordOperator(tenantId, actorUserId, "TRACKING_SCHEDULE_UPDATED",
                AuditTrail.Outcome.ALLOWED,
                Map.of("scheduleId", scheduleId.toString(), "enabled", enabled));
        return find(tenantId, scheduleId);
    }

    public void delete(long tenantId, UUID actorUserId, UUID scheduleId) {
        find(tenantId, scheduleId);
        schedules.delete(tenantId, scheduleId);
        audit.recordOperator(tenantId, actorUserId, "TRACKING_SCHEDULE_REMOVED",
                AuditTrail.Outcome.ALLOWED, Map.of("scheduleId", scheduleId.toString()));
    }

    /** Runs one attempt now without touching the automatic timer. */
    public TrackingScheduleRepository.RunView runNow(long tenantId, UUID actorUserId,
                                                     UUID scheduleId) {
        TrackingScheduleRepository.RunTarget target = schedules.findForRun(tenantId, scheduleId)
                .orElseThrow(() -> new OpsNotFoundException("tracking schedule"));
        TrackingScheduleRepository.RunView run = execute(target, "MANUAL", Instant.now());
        audit.recordOperator(tenantId, actorUserId, "TRACKING_SCHEDULE_RUN",
                AuditTrail.Outcome.ALLOWED,
                Map.of("scheduleId", scheduleId.toString(), "state", run.state(),
                        "runId", run.runId()));
        return run;
    }

    /** Sends every due schedule; returns how many were attempted. */
    public int dispatchDue(Instant now) {
        int attempted = 0;
        for (TrackingScheduleRepository.RunTarget target
                : schedules.due(now, properties.batchSize())) {
            try {
                execute(target, "SCHEDULE", now);
            } catch (RuntimeException e) {
                // The row stays due, so the next tick retries it; one broken
                // schedule must not stop the rest of the batch.
                log.warn("schedule {} could not run: {}", target.scheduleId(),
                        e.getClass().getSimpleName());
            }
            attempted++;
        }
        return attempted;
    }

    private TrackingScheduleRepository.RunView execute(TrackingScheduleRepository.RunTarget target,
                                                       String trigger, Instant now) {
        if (!target.clientEnabled()) {
            return finish(target, trigger, now, "SKIPPED", null, "CLIENT_DISABLED",
                    "NONE", null);
        }
        ApiClient client = clients.findByTenantAndId(target.tenantId(), target.clientId())
                .map(ApiClientRepository.StoredClient::client)
                .orElse(null);
        if (client == null) {
            return finish(target, trigger, now, "SKIPPED", null, "CLIENT_DISABLED",
                    "NONE", null);
        }
        QuotaService.Decision quota = quotas.consume(client);
        if (!quota.allowed()) {
            return finish(target, trigger, now, "SKIPPED", null, "RATE_LIMITED",
                    "NONE", null);
        }

        LocationRequestService.Outcome outcome;
        try {
            outcome = requests.create(client, new LocationRequestInput(target.msisdn(),
                    target.imsi(), target.purpose(), target.accuracyM(), "NORMAL",
                    "track-" + target.scheduleId() + "-" + UUID.randomUUID()), null);
        } catch (PrivacyNotSatisfiedException e) {
            return finish(target, trigger, now, "FAILED", null, "PRIVACY_NOT_SATISFIED",
                    "NONE", null);
        } catch (RequestRejectedException e) {
            return finish(target, trigger, now, "FAILED", null, e.code(), "NONE", null);
        }

        LocationRequestRepository.StoredRequest request = outcome.request();
        Optional<LocationResultRepository.StoredResult> result =
                requests.result(client, request);
        if (result.isEmpty()) {
            return finish(target, trigger, now, "NO_FIX", request.requestId(),
                    request.errorCode() == null ? "NO_RESULT" : request.errorCode(),
                    "NONE", null);
        }

        String notifyState = "NONE";
        String notifyDetail = null;
        if (target.notifyOnFix() && target.channelId() != null) {
            Optional<NotificationChannelRepository.DeliveryChannel> channel =
                    channels.findForDelivery(target.tenantId(), target.channelId());
            if (channel.isEmpty()) {
                notifyState = "FAILED";
                notifyDetail = "channel is missing";
            } else {
                String deliveryId = target.scheduleId() + ":" + UUID.randomUUID();
                TrackingNotifier.Delivery delivery = notifier.send(channel.get(), deliveryId,
                        event(target, request, result.get()));
                notifyState = delivery.state();
                notifyDetail = delivery.detail();
            }
        }
        return finish(target, trigger, now, "LOCATED", request.requestId(), null,
                notifyState, notifyDetail);
    }

    private TrackingScheduleRepository.RunView finish(TrackingScheduleRepository.RunTarget target,
                                                      String trigger, Instant now, String state,
                                                      UUID requestId, String errorCode,
                                                      String notifyState, String notifyDetail) {
        String detail = notifyDetail == null || notifyDetail.length() <= MAX_ERROR_LENGTH
                ? notifyDetail : notifyDetail.substring(0, MAX_ERROR_LENGTH);
        long runId = schedules.insertRun(target.tenantId(), target.scheduleId(), trigger, state,
                requestId, errorCode, notifyState, detail);
        Boolean advanced = "SCHEDULE".equals(trigger);
        schedules.markRun(target.scheduleId(), now, state, errorCode,
                advanced ? now.plusSeconds(target.intervalSeconds()) : null);
        return new TrackingScheduleRepository.RunView(runId, target.scheduleId(), trigger, state,
                requestId, errorCode, notifyState, detail, now);
    }

    private Map<String, Object> event(TrackingScheduleRepository.RunTarget target,
                                      LocationRequestRepository.StoredRequest request,
                                      LocationResultRepository.StoredResult result) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", TrackingNotifier.EVENT_TYPE);
        event.put("scheduleId", target.scheduleId().toString());
        event.put("trackedId", target.trackedId().toString());
        event.put("occurredAt", result.producedAt().toString());
        event.put("requestId", request.requestId().toString());
        event.put("resultId", result.resultId().toString());
        event.put("subject", Map.of("ref", target.subjectRef(),
                "label", target.subjectLabel()));
        Map<String, Object> position = new LinkedHashMap<>();
        position.put("latitude", trim(result.latitude()));
        position.put("longitude", trim(result.longitude()));
        position.put("uncertaintyM", result.uncertaintyM());
        position.put("confidence", result.confidence());
        position.put("ageSeconds", result.ageSeconds());
        position.put("method", result.method());
        position.put("shape", result.shape());
        event.put("position", position);
        return event;
    }

    private int requireInterval(Integer intervalSeconds) {
        long floor = properties.minIntervalSeconds();
        if (intervalSeconds == null || intervalSeconds < floor) {
            throw new OpsBadRequestException("INTERVAL_TOO_SHORT",
                    "intervalSeconds must be at least " + floor + "; licence metering is not"
                            + " wired in this deployment, so the floor is a conservative paging"
                            + " guard");
        }
        if (intervalSeconds > MAX_INTERVAL_SECONDS) {
            throw new OpsBadRequestException("INTERVAL_TOO_LONG",
                    "intervalSeconds must be at most " + MAX_INTERVAL_SECONDS);
        }
        return intervalSeconds;
    }

    private void requireUsableChannel(long tenantId, UUID channelId) {
        NotificationChannelRepository.ChannelView channel = channels.find(tenantId, channelId)
                .orElseThrow(() -> new OpsNotFoundException("notification channel"));
        if (!channel.enabled()) {
            throw new OpsBadRequestException("CHANNEL_DISABLED",
                    "the notification channel is disabled");
        }
        if (!"WEBHOOK".equals(channel.type())) {
            throw new OpsBadRequestException(NotificationChannelService.NOT_AVAILABLE,
                    "no notifier is built for " + channel.type());
        }
    }

    private static BigDecimal trim(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros();
    }

    public record Create(UUID trackedId, Integer intervalSeconds, Instant startsAt, Instant endsAt,
                         Integer accuracyMeters, Boolean notifyOnFix, UUID channelId) {
    }
}
