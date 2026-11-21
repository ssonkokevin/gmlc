package com.hamiltel.gmlc.location;

import com.hamiltel.gmlc.client.ApiClient;
import com.hamiltel.gmlc.client.CurrentClient;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The Le surface. The subject is accepted here and never echoed: the response
 * names the request, its state, its failure code and - when there is one -
 * the position with the envelope that qualifies it. An accepted request that
 * has already finished answers 200 with its final state; one still working
 * answers 202.
 */
@RestController
@RequestMapping("/v1/locations")
public class LocationController {

    private final LocationRequestService requests;

    public LocationController(LocationRequestService requests) {
        this.requests = requests;
    }

    @PostMapping
    public ResponseEntity<LocationView> create(@CurrentClient ApiClient client,
                                               @Valid @RequestBody LocationRequestInput input,
                                               @RequestHeader(value = "Idempotency-Key", required = false)
                                               String idempotencyKey) {
        LocationRequestService.Outcome outcome = requests.create(client, input, idempotencyKey);
        LocationRequestRepository.StoredRequest stored = outcome.request();

        HttpStatus status = outcome.replayed() || stored.stateValue().isFinal()
                ? HttpStatus.OK
                : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status)
                .header("Location", "/v1/locations/" + stored.requestId())
                .body(LocationView.of(stored, outcome.replayed(),
                        requests.result(client, stored).orElse(null)));
    }

    @GetMapping("/{requestId}")
    public LocationView get(@CurrentClient ApiClient client,
                            @PathVariable("requestId") UUID requestId) {
        LocationRequestRepository.StoredRequest stored = requests.get(client, requestId);
        return LocationView.of(stored, null, requests.result(client, stored).orElse(null));
    }

    public record LocationView(
            UUID requestId,
            String state,
            String purpose,
            String priority,
            Integer accuracyRequestedM,
            String errorCode,
            String errorDetail,
            Instant createdAt,
            Instant updatedAt,
            Instant deadlineAt,
            Instant completedAt,
            Boolean replayed,
            Result result) {

        static LocationView of(LocationRequestRepository.StoredRequest stored, Boolean replayed,
                               LocationResultRepository.StoredResult result) {
            return new LocationView(stored.requestId(), stored.state(), stored.purpose(),
                    stored.priority(), stored.accuracyRequestedM(), stored.errorCode(),
                    stored.errorDetail(), stored.createdAt(), stored.updatedAt(),
                    stored.deadlineAt(), stored.completedAt(), replayed,
                    result == null ? null : Result.of(result));
        }
    }

    /**
     * The position and everything that qualifies it. A client that reads only
     * latitude and longitude is reading a circle of {@code uncertaintyM}
     * metres, no older than {@code ageSeconds}, from the given method.
     */
    public record Result(
            String method,
            String shape,
            BigDecimal latitude,
            BigDecimal longitude,
            Integer uncertaintyM,
            Integer confidence,
            Integer ageSeconds,
            Integer accuracyRequestedM,
            Boolean accuracyFulfilled,
            String servingCell,
            String engine,
            String positioningNode,
            Instant producedAt,
            List<String> methodsAttempted) {

        static Result of(LocationResultRepository.StoredResult stored) {
            return new Result(stored.method(), stored.shape(), stored.latitude(),
                    stored.longitude(), stored.uncertaintyM(), stored.confidence(),
                    stored.ageSeconds(), stored.accuracyRequestedM(), stored.accuracyFulfilled(),
                    stored.servingCell(), stored.engine(), stored.positioningNode(),
                    stored.producedAt(), stored.methodsAttempted());
        }
    }
}
