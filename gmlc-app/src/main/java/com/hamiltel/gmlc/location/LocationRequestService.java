package com.hamiltel.gmlc.location;

import com.hamiltel.gmlc.audit.AuditTrail;
import com.hamiltel.gmlc.cdr.CdrRepository;
import com.hamiltel.gmlc.client.ApiClient;
import com.hamiltel.gmlc.config.GmlcProperties;
import com.hamiltel.gmlc.consent.ConsentService;
import com.hamiltel.gmlc.diameter.SignallingException;
import com.hamiltel.gmlc.geofence.GeofenceEvaluator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The Le request path: validate, check consent, record the request, then ask
 * the locator. A position is written to {@code location_result} before it is
 * returned, and a failure is a stable code with a CDR that says the same. The
 * gateway never returns a coordinate it did not obtain.
 *
 * <p>Le is synchronous: the answer travels in this response, so a request that
 * produced a result moves to DELIVERED before the body is written.
 */
@Service
public class LocationRequestService {

    private static final Logger log = LoggerFactory.getLogger(LocationRequestService.class);

    /** A client that has not been answered by then will see TIMEOUT once routing exists. */
    private static final Duration DEADLINE = Duration.ofSeconds(30);

    static final String NO_LCS_SOURCE = "NO_LCS_SOURCE";

    private static final String NOT_CONFIGURED = "SIGNALLING_NOT_CONFIGURED";

    private final LocationRequestRepository requests;
    private final ConsentService consents;
    private final SubscriberValidator validator;
    private final SubjectRefHasher hasher;
    private final CdrRepository cdrs;
    private final AuditTrail audit;
    private final LocationLocator locator;
    private final LocationResultRepository results;
    private final GeofenceEvaluator geofences;
    private final GmlcProperties properties;

    public LocationRequestService(LocationRequestRepository requests, ConsentService consents,
                                  SubscriberValidator validator, SubjectRefHasher hasher,
                                  CdrRepository cdrs, AuditTrail audit, LocationLocator locator,
                                  LocationResultRepository results, GeofenceEvaluator geofences,
                                  GmlcProperties properties) {
        this.requests = requests;
        this.consents = consents;
        this.validator = validator;
        this.hasher = hasher;
        this.cdrs = cdrs;
        this.audit = audit;
        this.locator = locator;
        this.results = results;
        this.geofences = geofences;
        this.properties = properties;
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
                input.accuracyMeters(), input.priorityOrDefault(), locator.protocol(), deadline);
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
        requests.advance(client.tenantId(), requestId, RequestState.ROUTED,
                RequestState.REQUESTED, null, null);

        Optional<LocationEstimate> estimate;
        try {
            estimate = locator.locate(new LocationLocator.Query(client.tenantId(),
                    stored.msisdn(), stored.imsi(), stored.accuracyRequestedM(),
                    stored.purpose(), properties.lcsClientName()));
        } catch (SignallingException e) {
            // With no peer configured nothing can source a position, which is
            // the same fact NO_LCS_SOURCE already names to clients.
            String code = NOT_CONFIGURED.equals(e.code()) ? NO_LCS_SOURCE : e.code();
            return finishFailure(client, requestId, subjectRef, privacy, stored, code, e.getMessage());
        }

        if (estimate.isEmpty()) {
            return finishFailure(client, requestId, subjectRef, privacy, stored, NO_LCS_SOURCE,
                    "the serving network named no cell to locate");
        }
        return finishAnswer(client, requestId, subjectRef, privacy, stored, estimate.get());
    }

    private Outcome finishAnswer(ApiClient client, UUID requestId, String subjectRef,
                                 ConsentService.PrivacyDecision privacy,
                                 LocationRequestRepository.StoredRequest stored,
                                 LocationEstimate estimate) {
        UUID resultId = UUID.randomUUID();
        results.insert(client.tenantId(), requestId, resultId, estimate);
        requests.attachResult(client.tenantId(), requestId, resultId);
        requests.advance(client.tenantId(), requestId, RequestState.REQUESTED,
                RequestState.ANSWERED, null, null);

        long responseMs = Duration.between(stored.createdAt(), Instant.now()).toMillis();
        cdrs.writeSuccess(client.tenantId(), requestId, client.clientId(), privacy.consentId(),
                subjectRef, estimate, locator.protocol(), operation(estimate.methodsAttempted()),
                responseMs);
        audit.recordForSubject(client.tenantId(), client.clientId(), "REQUEST_ANSWERED",
                subjectRef, requestId, AuditTrail.Outcome.ALLOWED,
                Map.of("method", estimate.method(), "uncertaintyM", estimate.uncertaintyM()));
        log.info("request {} answered by {} within {} m", requestId, estimate.method(),
                estimate.uncertaintyM());

        requests.advance(client.tenantId(), requestId, RequestState.ANSWERED,
                RequestState.DELIVERED, null, null);
        try {
            geofences.evaluate(client.tenantId(), subjectRef, requestId, resultId, estimate);
        } catch (RuntimeException e) {
            // A geofence defect must never take the position away from the client.
            log.warn("geofence evaluation failed for request {}: {}", requestId,
                    e.getClass().getSimpleName());
        }
        LocationRequestRepository.StoredRequest answered = requests.find(
                        client.tenantId(), client.clientId(), requestId)
                .orElseThrow(() -> new IllegalStateException("request vanished after delivery"));
        return new Outcome(answered, false);
    }

    private Outcome finishFailure(ApiClient client, UUID requestId, String subjectRef,
                                  ConsentService.PrivacyDecision privacy,
                                  LocationRequestRepository.StoredRequest stored,
                                  String code, String detail) {
        requests.advance(client.tenantId(), requestId, RequestState.REQUESTED,
                RequestState.FAILED, code, detail);
        long responseMs = Duration.between(stored.createdAt(), Instant.now()).toMillis();
        cdrs.writeFailure(client.tenantId(), requestId, client.clientId(), privacy.consentId(),
                subjectRef, code, stored.accuracyRequestedM(), locator.protocol(), responseMs);
        audit.record(client.tenantId(), client.clientId(), "REQUEST_FAILED", requestId,
                AuditTrail.Outcome.ERROR, Map.of("code", code));
        log.info("request {} failed closed: {}", requestId, code);

        LocationRequestRepository.StoredRequest failed = requests.find(
                        client.tenantId(), client.clientId(), requestId)
                .orElseThrow(() -> new IllegalStateException("request vanished after failure"));
        return new Outcome(failed, false);
    }

    /**
     * The operation a completed exchange used, from the locator's own record
     * of what it tried. Anything unrecognised stays null: the console then
     * reports the request as unattributed rather than guessing.
     */
    static String operation(java.util.List<String> attempted) {
        if (attempted == null) {
            return null;
        }
        if (attempted.contains("MAP_ATI")) {
            return "SRILCS_ATI";
        }
        if (attempted.contains("MAP_PSL")) {
            return "SRILCS_PSL";
        }
        if (attempted.contains("SLG_PLA") || attempted.contains("SLG_PLR")) {
            return "RIR_PLA";
        }
        return null;
    }

    public LocationRequestRepository.StoredRequest get(ApiClient client, UUID requestId) {
        return requests.find(client.tenantId(), client.clientId(), requestId)
                .orElseThrow(() -> new RequestNotFoundException(requestId));
    }

    public Optional<LocationResultRepository.StoredResult> result(ApiClient client,
                                                                  LocationRequestRepository.StoredRequest request) {
        return request.resultId() == null ? Optional.empty()
                : results.find(client.tenantId(), request.resultId());
    }

    public record Outcome(LocationRequestRepository.StoredRequest request, boolean replayed) {
    }
}
