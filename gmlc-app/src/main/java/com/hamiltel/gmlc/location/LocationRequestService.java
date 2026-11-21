package com.hamiltel.gmlc.location;

import com.hamiltel.gmlc.audit.AuditTrail;
import com.hamiltel.gmlc.cdr.CdrRepository;
import com.hamiltel.gmlc.client.ApiClient;
import com.hamiltel.gmlc.consent.ConsentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The Le request path: validate, check consent, record the request, then try
 * to route it. This build has no MAP or Diameter location client yet, so every
 * routed request ends FAILED with {@code NO_LCS_SOURCE} and a CDR that says
 * the same - it never invents a position.
 */
@Service
public class LocationRequestService {

    private static final Logger log = LoggerFactory.getLogger(LocationRequestService.class);

    /** A client that has not been answered by then will see TIMEOUT once routing exists. */
    private static final Duration DEADLINE = Duration.ofSeconds(30);

    static final String NO_LCS_SOURCE = "NO_LCS_SOURCE";

    private final LocationRequestRepository requests;
    private final ConsentService consents;
    private final SubscriberValidator validator;
    private final SubjectRefHasher hasher;
    private final CdrRepository cdrs;
    private final AuditTrail audit;

    public LocationRequestService(LocationRequestRepository requests, ConsentService consents,
                                  SubscriberValidator validator, SubjectRefHasher hasher,
                                  CdrRepository cdrs, AuditTrail audit) {
        this.requests = requests;
        this.consents = consents;
        this.validator = validator;
        this.hasher = hasher;
        this.cdrs = cdrs;
        this.audit = audit;
    }

    public Outcome create(ApiClient client, LocationRequestInput input, String headerIdempotencyKey) {
        String idempotencyKey = input.idempotencyOrHeader(headerIdempotencyKey);
        if (idempotencyKey != null && idempotencyKey.length() > 80) {
            throw new RequestRejectedException("IDEMPOTENCY_KEY_TOO_LONG",
                    "the idempotency key must be at most 80 characters");
        }

        if (idempotencyKey != null) {
            Optional<LocationRequestRepository.StoredRequest> replay =
                    requests.findByKey(client.tenantId(), client.clientId(), idempotencyKey);
            if (replay.isPresent()) {
                return new Outcome(replay.get(), true);
            }
        }

        if (!client.allowsPurpose(input.purpose())) {
            throw new RequestRejectedException("PURPOSE_NOT_ALLOWED",
                    "this client is not registered for that purpose");
        }
        // maxAccuracyM is the best (smallest) error the client is entitled to;
        // a request for a tighter figure would promise a position the
        // subscription does not buy.
        if (input.accuracyMeters() != null && input.accuracyMeters() < client.maxAccuracyM()) {
            throw new RequestRejectedException("ACCURACY_NOT_ALLOWED",
                    "this client may not ask for better than " + client.maxAccuracyM() + " m");
        }
        validator.validate(client.tenantId(), input.msisdn(), input.imsi());

        String subjectRef = hasher.ref(input.msisdn(), input.imsi());
        ConsentService.PrivacyDecision privacy =
                consents.check(client.tenantId(), client.clientId(), subjectRef, input.purpose());
        if (!privacy.allowed()) {
            audit.recordForSubject(client.tenantId(), client.clientId(), "PRIVACY_DENIED",
                    subjectRef, null, AuditTrail.Outcome.DENIED,
                    Map.of("purpose", input.purpose(), "basis", privacy.basis()));
            throw new PrivacyNotSatisfiedException(input.purpose());
        }

        UUID requestId = UUID.randomUUID();
        Instant deadline = Instant.now().plus(DEADLINE);
        boolean inserted = requests.insert(requestId, client.tenantId(), client.clientId(),
                idempotencyKey, input.purpose(), input.msisdn(), input.imsi(),
                input.accuracyMeters(), input.priorityOrDefault(), deadline);
        if (!inserted) {
            return requests.findByKey(client.tenantId(), client.clientId(), idempotencyKey)
                    .map(existing -> new Outcome(existing, true))
                    .orElseThrow(() -> new IllegalStateException(
                            "request insert conflicted but the idempotency key is not present"));
        }

        audit.recordForSubject(client.tenantId(), client.clientId(), "REQUEST_ACCEPTED",
                subjectRef, requestId, AuditTrail.Outcome.ALLOWED,
                Map.of("purpose", input.purpose(), "basis", privacy.basis()));

        LocationRequestRepository.StoredRequest stored = requests.find(
                        client.tenantId(), client.clientId(), requestId)
                .orElseThrow(() -> new IllegalStateException("request vanished after insert"));

        requests.advance(client.tenantId(), requestId, RequestState.RECEIVED, RequestState.AUTHORISED,
                null, null);
        requests.advance(client.tenantId(), requestId, RequestState.AUTHORISED,
                RequestState.PRIVACY_CHECKED, null, null);
        requests.advance(client.tenantId(), requestId, RequestState.PRIVACY_CHECKED,
                RequestState.ROUTED, null, null);

        // No MAP, SLh/SLg or SLs client is configured in this build. Saying so
        // is the honest outcome; the error code is stable for clients.
        String detail = "no MAP or Diameter location client is configured in this build";
        requests.advance(client.tenantId(), requestId, RequestState.ROUTED, RequestState.FAILED,
                NO_LCS_SOURCE, detail);
        long responseMs = Duration.between(stored.createdAt(), Instant.now()).toMillis();
        cdrs.writeFailure(client.tenantId(), requestId, client.clientId(), privacy.consentId(),
                subjectRef, NO_LCS_SOURCE, stored.accuracyRequestedM(), responseMs);
        audit.record(client.tenantId(), client.clientId(), "REQUEST_FAILED", requestId,
                AuditTrail.Outcome.ERROR, Map.of("code", NO_LCS_SOURCE));
        log.info("request {} failed closed: {}", requestId, NO_LCS_SOURCE);

        LocationRequestRepository.StoredRequest failed = requests.find(
                        client.tenantId(), client.clientId(), requestId)
                .orElseThrow(() -> new IllegalStateException("request vanished after failure"));
        return new Outcome(failed, false);
    }

    public LocationRequestRepository.StoredRequest get(ApiClient client, UUID requestId) {
        return requests.find(client.tenantId(), client.clientId(), requestId)
                .orElseThrow(() -> new RequestNotFoundException(requestId));
    }

    public record Outcome(LocationRequestRepository.StoredRequest request, boolean replayed) {
    }
}
