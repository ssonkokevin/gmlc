package com.hamiltel.gmlc.ops;

import com.hamiltel.gmlc.audit.AuditTrail;
import com.hamiltel.gmlc.client.ApiClientRepository;
import com.hamiltel.gmlc.location.LocationRequestInput;
import com.hamiltel.gmlc.location.LocationRequestRepository;
import com.hamiltel.gmlc.location.LocationRequestService;
import com.hamiltel.gmlc.location.PrivacyNotSatisfiedException;
import com.hamiltel.gmlc.location.RequestRejectedException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Operating a location request from the console. The request goes through the
 * same service an API client uses, so purpose, accuracy, consent, licence and
 * state machine are identical; what differs is the actor. The client is chosen
 * from this tenant's enabled clients, and the person is written to the audit
 * trail separately from the client.
 */
@RestController
@RequestMapping("/v1/ops")
public class OpsLocationController {

    private final ApiClientRepository clients;
    private final LocationRequestService requests;
    private final AuditTrail audit;

    public OpsLocationController(ApiClientRepository clients, LocationRequestService requests,
                                 AuditTrail audit) {
        this.clients = clients;
        this.requests = requests;
        this.audit = audit;
    }

    @GetMapping("/clients")
    @OpsRequires(OpsPermission.LOCATE)
    public List<ApiClientRepository.ClientView> clients(@OpsCurrentSession OpsContext context) {
        return clients.listEnabled(context.tenantId());
    }

    @PostMapping("/locations")
    @OpsRequires(OpsPermission.LOCATE)
    public ResponseEntity<Submission> submit(@OpsCurrentSession OpsContext context,
                                             @Valid @RequestBody OpsLocationRequest body) {
        ApiClientRepository.StoredClient stored = clients
                .findByTenantAndId(context.tenantId(), body.clientId())
                .filter(ApiClientRepository.StoredClient::enabled)
                .orElseThrow(() -> new OpsNotFoundException("client"));

        LocationRequestService.Outcome outcome;
        try {
            outcome = requests.create(stored.client(), body.toInput(), null);
        } catch (RequestRejectedException rejected) {
            audit.recordOperator(context.tenantId(), context.user().userId(),
                    "OPS_LOCATION_SUBMITTED", AuditTrail.Outcome.DENIED,
                    Map.of("clientId", body.clientId().toString(), "purpose", body.purpose(),
                            "reason", rejected.code()));
            throw rejected;
        } catch (PrivacyNotSatisfiedException denied) {
            audit.recordOperator(context.tenantId(), context.user().userId(),
                    "OPS_LOCATION_SUBMITTED", AuditTrail.Outcome.DENIED,
                    Map.of("clientId", body.clientId().toString(), "purpose", body.purpose(),
                            "reason", "PRIVACY_NOT_SATISFIED"));
            throw denied;
        }

        LocationRequestRepository.StoredRequest request = outcome.request();
        audit.recordOperator(context.tenantId(), context.user().userId(),
                "OPS_LOCATION_SUBMITTED", AuditTrail.Outcome.ALLOWED,
                Map.of("requestId", request.requestId().toString(),
                        "clientId", request.clientId().toString(),
                        "purpose", request.purpose()));

        HttpStatus status = outcome.replayed() || request.stateValue().isFinal()
                ? HttpStatus.OK
                : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status)
                .header("Location", "/v1/ops/requests/" + request.requestId())
                .body(Submission.of(request, outcome.replayed()));
    }

    public record OpsLocationRequest(
            @NotNull UUID clientId,
            @Pattern(regexp = "[0-9]{7,15}", message = "must be 7-15 E.164 digits")
            String msisdn,
            @Pattern(regexp = "[0-9]{14,15}", message = "must be a 14 or 15 digit IMSI")
            String imsi,
            @NotBlank
            @Pattern(regexp = "[A-Z_]{3,32}", message = "must be an uppercase purpose code")
            String purpose,
            @Min(1) @Max(100_000) Integer accuracyMeters,
            @Pattern(regexp = "LOW|NORMAL|HIGH", message = "must be LOW, NORMAL or HIGH")
            String priority,
            @Size(max = 80) String idempotencyKey) {

        LocationRequestInput toInput() {
            return new LocationRequestInput(msisdn, imsi, purpose, accuracyMeters, priority,
                    idempotencyKey);
        }
    }

    public record Submission(UUID requestId, String state, String purpose, String priority,
                             Integer accuracyRequestedM, String errorCode, String errorDetail,
                             Instant createdAt, Instant updatedAt, Instant deadlineAt,
                             Instant completedAt, boolean replayed) {

        static Submission of(LocationRequestRepository.StoredRequest request, boolean replayed) {
            return new Submission(request.requestId(), request.state(), request.purpose(),
                    request.priority(), request.accuracyRequestedM(), request.errorCode(),
                    request.errorDetail(), request.createdAt(), request.updatedAt(),
                    request.deadlineAt(), request.completedAt(), replayed);
        }
    }
}
