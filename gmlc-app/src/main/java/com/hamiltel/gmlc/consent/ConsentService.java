package com.hamiltel.gmlc.consent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamiltel.gmlc.audit.AuditTrail;
import com.hamiltel.gmlc.client.ApiClient;
import com.hamiltel.gmlc.config.GmlcProperties;
import com.hamiltel.gmlc.location.RequestRejectedException;
import com.hamiltel.gmlc.location.SubjectRefHasher;
import com.hamiltel.gmlc.location.SubscriberValidator;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Consent is the only thing that lets a non-emergency request through. It is
 * stored per subject pseudonym, purpose and client, with an expiry and the
 * evidence the client supplied. Revocation is a status change, never a delete.
 */
@Service
public class ConsentService {

    private static final int MAX_EVIDENCE_BYTES = 8 * 1024;

    private final ConsentRepository consents;
    private final SubjectRefHasher hasher;
    private final SubscriberValidator validator;
    private final AuditTrail audit;
    private final GmlcProperties properties;
    private final ObjectMapper mapper;

    public ConsentService(ConsentRepository consents, SubjectRefHasher hasher,
                          SubscriberValidator validator, AuditTrail audit,
                          GmlcProperties properties, ObjectMapper mapper) {
        this.consents = consents;
        this.hasher = hasher;
        this.validator = validator;
        this.audit = audit;
        this.properties = properties;
        this.mapper = mapper;
    }

    public ConsentRepository.Consent grant(ApiClient client, ConsentGrantRequest request) {
        if (!client.allowsPurpose(request.purpose())) {
            throw new RequestRejectedException("PURPOSE_NOT_ALLOWED",
                    "this client is not registered for that purpose");
        }
        validator.validate(client.tenantId(), request.msisdn(), request.imsi());
        String subjectRef = hasher.ref(request.msisdn(), request.imsi());
        String evidence = evidenceJson(request);

        UUID consentId = consents.upsert(client.tenantId(), UUID.randomUUID(), subjectRef,
                client.clientId(), request.purpose(), request.channel(), request.expiresAt(),
                evidence);
        ConsentRepository.Consent stored = consents.find(client.tenantId(), client.clientId(), consentId)
                .orElseThrow(() -> new IllegalStateException("consent vanished after upsert"));

        audit.recordForSubject(client.tenantId(), client.clientId(), "CONSENT_GRANTED", subjectRef,
                null, AuditTrail.Outcome.ALLOWED,
                Map.of("consentId", consentId.toString(), "purpose", request.purpose()));
        return stored;
    }

    public ConsentRepository.Consent find(long tenantId, UUID clientId, UUID consentId) {
        return consents.find(tenantId, clientId, consentId)
                .orElseThrow(() -> new ConsentNotFoundException(consentId));
    }

    public void revoke(long tenantId, UUID clientId, UUID consentId) {
        ConsentRepository.Consent existing = find(tenantId, clientId, consentId);
        if (!consents.revoke(tenantId, clientId, consentId)) {
            throw new ConsentNotFoundException(consentId);
        }
        audit.recordForSubject(tenantId, clientId, "CONSENT_REVOKED", existing.subjectRef(),
                null, AuditTrail.Outcome.ALLOWED,
                Map.of("consentId", consentId.toString(), "purpose", existing.purpose()));
    }

    /**
     * EMERGENCY may proceed without stored consent only when the deployment
     * enables the override; the audit row records which basis was used.
     */
    public PrivacyDecision check(long tenantId, UUID clientId, String subjectRef, String purpose) {
        Optional<ConsentRepository.Consent> active =
                consents.findActive(tenantId, subjectRef, purpose, clientId);
        if (active.isPresent()) {
            return new PrivacyDecision(true, "CONSENT", active.get().consentId());
        }
        if ("EMERGENCY".equals(purpose) && properties.emergencyOverride()) {
            return new PrivacyDecision(true, "EMERGENCY_OVERRIDE", null);
        }
        return new PrivacyDecision(false, "NO_CONSENT", null);
    }

    private String evidenceJson(ConsentGrantRequest request) {
        if (request.evidence() == null || request.evidence().isNull()) {
            return null;
        }
        try {
            String json = mapper.writeValueAsString(request.evidence());
            if (json.getBytes(StandardCharsets.UTF_8).length > MAX_EVIDENCE_BYTES) {
                throw new RequestRejectedException("EVIDENCE_TOO_LARGE",
                        "consent evidence must be at most " + MAX_EVIDENCE_BYTES + " bytes");
            }
            return json;
        } catch (JsonProcessingException e) {
            throw new RequestRejectedException("EVIDENCE_NOT_SERIALISABLE",
                    "consent evidence could not be stored as JSON");
        }
    }

    public record PrivacyDecision(boolean allowed, String basis, UUID consentId) {
    }
}
