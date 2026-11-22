package com.hamiltel.gmlc.mlp;

import com.hamiltel.gmlc.audit.AuditTrail;
import com.hamiltel.gmlc.client.ApiClient;
import com.hamiltel.gmlc.client.CurrentClient;
import com.hamiltel.gmlc.location.LocationRequestInput;
import com.hamiltel.gmlc.location.LocationRequestRepository;
import com.hamiltel.gmlc.location.LocationRequestService;
import com.hamiltel.gmlc.location.LocationResultRepository;
import com.hamiltel.gmlc.location.PrivacyNotSatisfiedException;
import com.hamiltel.gmlc.location.RequestRejectedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The OMA MLP 3.x face. The adapter is a translation layer over the same Le
 * request path: the API key is the LCS client, consent and audit decisions are
 * the Le ones, and there is no second route to a position.
 *
 * <p>Only the standard location immediate service ({@code slir}/{@code slia})
 * is implemented. Triggered reporting would require this gateway to push
 * reports to a caller-supplied URL; it is refused with the standard "service
 * not supported" result rather than half-built or made an outbound-request
 * hazard.
 */
@RestController
public class MlpController {

    private static final Logger log = LoggerFactory.getLogger(MlpController.class);

    private static final int MAX_BODY_CHARS = 64 * 1024;

    private final MlpParser parser = new MlpParser();
    private final LocationRequestService requests;
    private final AuditTrail audit;

    public MlpController(LocationRequestService requests, AuditTrail audit) {
        this.requests = requests;
        this.audit = audit;
    }

    @PostMapping(path = "/mlp", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> handle(@CurrentClient ApiClient client,
                                         @RequestBody(required = false) String body,
                                         @RequestHeader(value = "Idempotency-Key", required = false)
                                         String idempotencyKey) {
        if (body == null || body.isBlank()) {
            return xml(HttpStatus.OK, MlpAnswer.refusal("slia", "3.0.0", 105,
                    "the request body is empty", Instant.now()));
        }
        if (body.length() > MAX_BODY_CHARS) {
            return xml(HttpStatus.PAYLOAD_TOO_LARGE, MlpAnswer.refusal("slia", "3.0.0", 105,
                    "the request body is larger than 64k characters", Instant.now()));
        }

        MlpRequest request;
        try {
            request = parser.parse(body);
        } catch (MlpFault fault) {
            return xml(HttpStatus.OK, MlpAnswer.fault(fault));
        }

        if (!"slir".equals(request.service())) {
            audit.record(client.tenantId(), client.clientId(), "MLP_SERVICE_REFUSED", null,
                    AuditTrail.Outcome.DENIED, Map.of("service", request.service()));
            return xml(HttpStatus.OK, MlpAnswer.refusal(request.answerElement(),
                    request.answerVersion(), 108, "the " + request.service()
                            + " service is not implemented; this gateway answers slir only",
                    Instant.now()));
        }

        String purpose;
        try {
            purpose = MlpParser.purpose(request, client.purposes());
        } catch (MlpFault fault) {
            return xml(HttpStatus.OK, MlpAnswer.refusal(request.answerElement(),
                    request.answerVersion(), fault.resid(), fault.addInfo(), Instant.now()));
        }

        List<MlpAnswer.Position> positions = new ArrayList<>();
        for (int i = 0; i < request.subjects().size(); i++) {
            MlpRequest.MlpSubject subject = request.subjects().get(i);
            positions.add(locate(client, subject, purpose, request, idempotencyKey, i));
        }
        return xml(HttpStatus.OK, MlpAnswer.positions(request.answerElement(),
                request.answerVersion(), positions, Instant.now()));
    }

    private MlpAnswer.Position locate(ApiClient client, MlpRequest.MlpSubject subject,
                                      String purpose, MlpRequest request,
                                      String idempotencyKey, int index) {
        LocationRequestInput input = new LocationRequestInput(
                subject.msisdn() ? subject.value() : null,
                subject.msisdn() ? null : subject.value(),
                purpose, request.accuracyMeters(), request.priority(), key(idempotencyKey, index));
        try {
            LocationRequestService.Outcome outcome =
                    requests.create(client, input, input.idempotencyKey());
            LocationRequestRepository.StoredRequest stored = outcome.request();
            if (stored.stateValue().isFinal() && stored.errorCode() != null) {
                return MlpAnswer.Position.error(subject, residFor(stored.errorCode()),
                        addInfoFor(stored.errorCode()));
            }
            Optional<LocationResultRepository.StoredResult> result = requests.result(client, stored);
            if (result.isEmpty()) {
                return MlpAnswer.Position.error(subject, 1, "the gateway stored no position");
            }
            return MlpAnswer.Position.of(subject, result.get());
        } catch (PrivacyNotSatisfiedException e) {
            return MlpAnswer.Position.error(subject, 602,
                    "no consent covers this subject for that purpose");
        } catch (RequestRejectedException e) {
            return MlpAnswer.Position.error(subject, residFor(e.code()),
                    e.code() + ": " + e.getMessage());
        } catch (RuntimeException e) {
            log.warn("mlp request failed for client {}: {}", client.clientId(),
                    e.getClass().getSimpleName());
            return MlpAnswer.Position.error(subject, 1, "the gateway could not answer");
        }
    }

    /**
     * MLP requests carry several msids; the Le idempotency key is per request,
     * so each subject gets its own derived key. Without a header there is
     * nothing to replay against.
     */
    private static String key(String idempotencyKey, int index) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 70) {
            return null;
        }
        return idempotencyKey + "#" + (index + 1);
    }

    static int residFor(String leCode) {
        return switch (leCode) {
            case "ACCURACY_NOT_ALLOWED" -> 601;
            case "PURPOSE_NOT_ALLOWED" -> 3;
            case "IMSI_NOT_IN_TENANT_PLMN" -> 4;
            case "SUBJECT_REQUIRED", "IDEMPOTENCY_KEY_TOO_LONG", "IMSI_REQUIRED" -> 110;
            case "NO_LCS_SOURCE", "SIGNALLING_NOT_CONFIGURED" -> 605;
            case "TIMEOUT" -> 7;
            default -> 6;
        };
    }

    static String addInfoFor(String leCode) {
        return switch (leCode) {
            case "NO_LCS_SOURCE", "SIGNALLING_NOT_CONFIGURED" ->
                    "NO_LCS_SOURCE: this gateway has no signalling source configured";
            default -> leCode;
        };
    }

    private static ResponseEntity<String> xml(HttpStatus status, String body) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_XML)
                .body(body);
    }
}
