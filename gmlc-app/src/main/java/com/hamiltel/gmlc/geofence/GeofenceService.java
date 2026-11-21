package com.hamiltel.gmlc.geofence;

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

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Geofence administration from the console.
 *
 * <p>Arming is a privacy action, so it names a client and a purpose and is
 * refused without an active consent exactly like a locate. The arm itself
 * stores only the pseudonymous subject reference plus a masked label; events
 * are derived from location results the gateway stored after a governed
 * request, never from a new network subscription.
 */
@Service
public class GeofenceService {

    private final GeofenceRepository geofences;
    private final ApiClientRepository clients;
    private final ConsentService consents;
    private final SubscriberValidator validator;
    private final SubjectRefHasher hasher;
    private final AuditTrail audit;

    public GeofenceService(GeofenceRepository geofences, ApiClientRepository clients,
                           ConsentService consents, SubscriberValidator validator,
                           SubjectRefHasher hasher, AuditTrail audit) {
        this.geofences = geofences;
        this.clients = clients;
        this.consents = consents;
        this.validator = validator;
        this.hasher = hasher;
        this.audit = audit;
    }

    public GeofenceRepository.GeofenceView create(long tenantId, UUID actorUserId,
                                                  GeofenceGeometry geometry, boolean reportInside,
                                                  String name, String description, String group) {
        String cleanName = requiredName(name);
        if (geofences.exists(tenantId, cleanName)) {
            throw new OpsConflictException("GEOFENCE_NAME_TAKEN",
                    "a geofence with that name already exists");
        }
        UUID geofenceId = UUID.randomUUID();
        geofences.insert(tenantId, geofenceId, cleanName, blankToNull(description),
                blankToNull(group), geometry, reportInside, actorUserId);
        audit.recordOperator(tenantId, actorUserId, "GEOFENCE_CREATED",
                AuditTrail.Outcome.ALLOWED,
                Map.of("geofenceId", geofenceId.toString(), "shape", geometry.shapeName()));
        return find(tenantId, geofenceId);
    }

    public GeofenceRepository.GeofenceView update(long tenantId, UUID actorUserId,
                                                  UUID geofenceId, Patch patch) {
        GeofenceRepository.GeofenceView existing = find(tenantId, geofenceId);
        String name = existing.name();
        if (patch.name() != null) {
            name = requiredName(patch.name());
            if (!name.equals(existing.name()) && geofences.existsOther(tenantId, geofenceId, name)) {
                throw new OpsConflictException("GEOFENCE_NAME_TAKEN",
                        "a geofence with that name already exists");
            }
        }
        String description = patch.description() == null ? existing.description()
                : blankToNull(patch.description());
        String group = patch.group() == null ? existing.group() : blankToNull(patch.group());
        boolean reportInside = patch.reportInside() == null ? existing.reportInside()
                : patch.reportInside();
        boolean enabled = patch.enabled() == null ? existing.enabled() : patch.enabled();

        geofences.update(tenantId, geofenceId, name, description, group, reportInside, enabled);
        audit.recordOperator(tenantId, actorUserId, "GEOFENCE_UPDATED",
                AuditTrail.Outcome.ALLOWED,
                Map.of("geofenceId", geofenceId.toString(), "enabled", enabled));
        return find(tenantId, geofenceId);
    }

    public GeofenceRepository.GeofenceView find(long tenantId, UUID geofenceId) {
        return geofences.find(tenantId, geofenceId)
                .orElseThrow(() -> new OpsNotFoundException("geofence"));
    }

    public ArmResult arm(long tenantId, UUID actorUserId, UUID geofenceId, Arm arm) {
        GeofenceRepository.GeofenceView geofence = find(tenantId, geofenceId);
        if (!geofence.enabled()) {
            throw new OpsConflictException("GEOFENCE_DISABLED",
                    "a disabled geofence cannot be armed");
        }

        ApiClientRepository.StoredClient client = clients
                .findByTenantAndId(tenantId, arm.clientId())
                .filter(ApiClientRepository.StoredClient::enabled)
                .orElseThrow(() -> new OpsNotFoundException("client"));
        if (!client.client().allowsPurpose(arm.purpose())) {
            audit.recordOperator(tenantId, actorUserId, "GEOFENCE_ARMED",
                    AuditTrail.Outcome.DENIED,
                    Map.of("geofenceId", geofenceId.toString(), "reason", "PURPOSE_NOT_ALLOWED"));
            throw new RequestRejectedException("PURPOSE_NOT_ALLOWED",
                    "this client is not registered for that purpose");
        }
        validator.validate(tenantId, arm.msisdn(), arm.imsi());

        String subjectRef = hasher.ref(arm.msisdn(), arm.imsi());
        ConsentService.PrivacyDecision privacy =
                consents.check(tenantId, arm.clientId(), subjectRef, arm.purpose());
        if (!privacy.allowed()) {
            audit.recordOperator(tenantId, actorUserId, "GEOFENCE_ARMED",
                    AuditTrail.Outcome.DENIED,
                    Map.of("geofenceId", geofenceId.toString(),
                            "reason", "PRIVACY_NOT_SATISFIED", "purpose", arm.purpose()));
            throw new PrivacyNotSatisfiedException(arm.purpose());
        }

        String label = OpsMasking.mask(arm.msisdn() == null || arm.msisdn().isBlank()
                ? arm.imsi() : arm.msisdn());
        boolean created = geofences.arm(geofenceId, tenantId, subjectRef, label, actorUserId);
        audit.recordOperator(tenantId, actorUserId, "GEOFENCE_ARMED",
                AuditTrail.Outcome.ALLOWED,
                Map.of("geofenceId", geofenceId.toString(), "created", created,
                        "purpose", arm.purpose(), "clientId", arm.clientId().toString()));
        Optional<GeofenceRepository.ArmView> stored = geofences.arms(tenantId, geofenceId).stream()
                .filter(row -> row.subjectRef().equals(subjectRef))
                .findFirst();
        return new ArmResult(created, stored.orElse(null));
    }

    public void disarm(long tenantId, UUID actorUserId, UUID geofenceId, String subjectRef) {
        find(tenantId, geofenceId);
        if (subjectRef == null || !subjectRef.matches("[0-9a-f]{64}")) {
            throw new OpsNotFoundException("arm");
        }
        if (!geofences.disarm(geofenceId, tenantId, subjectRef)) {
            throw new OpsNotFoundException("arm");
        }
        audit.recordOperator(tenantId, actorUserId, "GEOFENCE_DISARMED",
                AuditTrail.Outcome.ALLOWED, Map.of("geofenceId", geofenceId.toString()));
    }

    private static String requiredName(String name) {
        if (name == null || name.isBlank()) {
            throw new OpsBadRequestException("GEOFENCE_NAME_REQUIRED", "a name is required");
        }
        String trimmed = name.trim();
        if (trimmed.length() > 128) {
            throw new OpsBadRequestException("GEOFENCE_NAME_TOO_LONG",
                    "the name must be at most 128 characters");
        }
        return trimmed;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record Arm(UUID clientId, String msisdn, String imsi, String purpose) {
    }

    public record ArmResult(boolean created, GeofenceRepository.ArmView arm) {
    }

    public record Patch(String name, String description, String group, Boolean reportInside,
                        Boolean enabled) {
    }
}
