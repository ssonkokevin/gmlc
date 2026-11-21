package com.hamiltel.gmlc.tracking;

import com.hamiltel.gmlc.audit.AuditTrail;
import com.hamiltel.gmlc.client.ApiClientRepository;
import com.hamiltel.gmlc.consent.ConsentService;
import com.hamiltel.gmlc.location.PrivacyNotSatisfiedException;
import com.hamiltel.gmlc.location.RequestRejectedException;
import com.hamiltel.gmlc.location.SubjectRefHasher;
import com.hamiltel.gmlc.location.SubscriberValidator;
import com.hamiltel.gmlc.ops.OpsBadRequestException;
import com.hamiltel.gmlc.ops.OpsConflictException;
import com.hamiltel.gmlc.ops.OpsMasking;
import com.hamiltel.gmlc.ops.OpsNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The tracking roster.
 *
 * <p>Adding a subject is a privacy action: it names a client and one of the
 * four tracking purposes, and it is refused without an active consent exactly
 * like a locate. The roster stores the pseudonymous reference plus a masked
 * label; the identifier itself stays server-side so the trail can resolve the
 * requests the network already carried, and no response returns it.
 *
 * <p>A tracked subject is not a live feed. The gateway has no deferred or
 * periodic location subscription in this deployment, so a trail point is
 * always a stored result from a governed request.
 */
@Service
public class TrackingService {

    /** The categories the tracking screen offers; each is a purpose code. */
    public static final Set<String> PURPOSES = Set.of(
            "SAFETY", "FLEET_MANAGEMENT", "COMMERCIAL_LBS", "IOT_ASSET");

    private final TrackingRepository tracking;
    private final ApiClientRepository clients;
    private final ConsentService consents;
    private final SubscriberValidator validator;
    private final SubjectRefHasher hasher;
    private final AuditTrail audit;

    public TrackingService(TrackingRepository tracking, ApiClientRepository clients,
                           ConsentService consents, SubscriberValidator validator,
                           SubjectRefHasher hasher, AuditTrail audit) {
        this.tracking = tracking;
        this.clients = clients;
        this.consents = consents;
        this.validator = validator;
        this.hasher = hasher;
        this.audit = audit;
    }

    public TrackingRepository.TrackedView register(long tenantId, UUID actorUserId,
                                                   Register command) {
        String purpose = command.purpose() == null ? "" : command.purpose().trim();
        if (!PURPOSES.contains(purpose)) {
            throw new OpsBadRequestException("TRACKING_PURPOSE_NOT_SUPPORTED",
                    "purpose must be SAFETY, FLEET_MANAGEMENT, COMMERCIAL_LBS or IOT_ASSET");
        }
        if (bothIdentifiers(command)) {
            throw new OpsBadRequestException("SUBJECT_IDENTIFIER_AMBIGUOUS",
                    "provide either an MSISDN or an IMSI, not both");
        }

        ApiClientRepository.StoredClient client = clients
                .findByTenantAndId(tenantId, command.clientId())
                .filter(ApiClientRepository.StoredClient::enabled)
                .orElseThrow(() -> new OpsNotFoundException("client"));
        if (!client.client().allowsPurpose(purpose)) {
            refuse(tenantId, actorUserId, command, purpose, "PURPOSE_NOT_ALLOWED");
            throw new RequestRejectedException("PURPOSE_NOT_ALLOWED",
                    "this client is not registered for that purpose");
        }
        validator.validate(tenantId, command.msisdn(), command.imsi());

        String subjectRef = hasher.ref(command.msisdn(), command.imsi());
        ConsentService.PrivacyDecision privacy =
                consents.check(tenantId, command.clientId(), subjectRef, purpose);
        if (!privacy.allowed()) {
            refuse(tenantId, actorUserId, command, purpose, "PRIVACY_NOT_SATISFIED");
            throw new PrivacyNotSatisfiedException(purpose);
        }

        UUID trackedId = UUID.randomUUID();
        String label = OpsMasking.mask(isBlank(command.msisdn())
                ? command.imsi() : command.msisdn());
        boolean created = tracking.insert(trackedId, tenantId, command.clientId(), subjectRef,
                blankToNull(command.msisdn()), blankToNull(command.imsi()), label, purpose,
                actorUserId);
        if (!created) {
            throw new OpsConflictException("SUBJECT_ALREADY_TRACKED",
                    "this client already tracks the subject for that purpose");
        }
        audit.recordOperator(tenantId, actorUserId, "TRACKING_SUBJECT_ADDED",
                AuditTrail.Outcome.ALLOWED,
                Map.of("trackedId", trackedId.toString(),
                        "clientId", command.clientId().toString(),
                        "purpose", purpose, "basis", privacy.basis()));
        return find(tenantId, trackedId);
    }

    public List<TrackingRepository.TrackedView> list(long tenantId) {
        return tracking.list(tenantId);
    }

    public TrackingRepository.TrackedView find(long tenantId, UUID trackedId) {
        return tracking.find(tenantId, trackedId)
                .orElseThrow(() -> new OpsNotFoundException("tracked subject"));
    }

    public List<TrackingRepository.FixView> trail(long tenantId, UUID trackedId, int limit) {
        find(tenantId, trackedId);
        return tracking.trail(tenantId, trackedId, limit);
    }

    public TrackingRepository.TrackedView setEnabled(long tenantId, UUID actorUserId,
                                                     UUID trackedId, boolean enabled) {
        find(tenantId, trackedId);
        tracking.setEnabled(tenantId, trackedId, enabled);
        audit.recordOperator(tenantId, actorUserId, "TRACKING_SUBJECT_UPDATED",
                AuditTrail.Outcome.ALLOWED,
                Map.of("trackedId", trackedId.toString(), "enabled", enabled));
        return find(tenantId, trackedId);
    }

    public void remove(long tenantId, UUID actorUserId, UUID trackedId) {
        find(tenantId, trackedId);
        if (!tracking.delete(tenantId, trackedId)) {
            throw new OpsNotFoundException("tracked subject");
        }
        audit.recordOperator(tenantId, actorUserId, "TRACKING_SUBJECT_REMOVED",
                AuditTrail.Outcome.ALLOWED, Map.of("trackedId", trackedId.toString()));
    }

    /**
     * The client-facing last-known answer. A subject this client does not own
     * is a 404, never a hint. A subject that exists but cannot be shown says
     * why in {@code found=false}; consent is re-checked on every read, so a
     * revocation takes effect even though the roster row remains.
     */
    public LastKnown lastKnown(long tenantId, UUID clientId, UUID trackedId) {
        TrackingRepository.OwnedSubject tracked = tracking
                .findOwner(tenantId, clientId, trackedId)
                .orElseThrow(() -> new TrackedSubjectNotFoundException(trackedId));
        if (!tracked.enabled()) {
            return LastKnown.unknown(tracked, "TRACKING_DISABLED");
        }
        if (!consents.check(tenantId, clientId, tracked.subjectRef(), tracked.purpose()).allowed()) {
            return LastKnown.unknown(tracked, "NO_CONSENT");
        }
        if (tracked.lastFix() == null) {
            return LastKnown.unknown(tracked, "NO_LOCATION_YET");
        }
        return LastKnown.found(tracked);
    }

    private void refuse(long tenantId, UUID actorUserId, Register command, String purpose,
                        String reason) {
        audit.recordOperator(tenantId, actorUserId, "TRACKING_SUBJECT_ADDED",
                AuditTrail.Outcome.DENIED,
                Map.of("clientId", command.clientId().toString(), "purpose", purpose,
                        "reason", reason));
    }

    private static boolean bothIdentifiers(Register command) {
        return !isBlank(command.msisdn()) && !isBlank(command.imsi());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String blankToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    public record Register(UUID clientId, String msisdn, String imsi, String purpose) {
    }

    public record LastKnown(boolean found, String reason, UUID trackedId, UUID clientId,
                            String purpose, TrackingRepository.FixView fix) {

        static LastKnown found(TrackingRepository.OwnedSubject tracked) {
            return new LastKnown(true, null, tracked.trackedId(), tracked.clientId(),
                    tracked.purpose(), tracked.lastFix());
        }

        static LastKnown unknown(TrackingRepository.OwnedSubject tracked, String reason) {
            return new LastKnown(false, reason, tracked.trackedId(), tracked.clientId(),
                    tracked.purpose(), null);
        }
    }
}
