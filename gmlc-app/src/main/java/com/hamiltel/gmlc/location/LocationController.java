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

import java.time.Instant;
import java.util.UUID;

/**
 * The Le surface. The subject is accepted here and never echoed: the response
 * names the request, its state and its failure code, nothing about the
 * subscriber. An accepted request that has already finished answers 200 with
 * its final state; one still working answers 202.
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
                .body(LocationView.of(stored, outcome.replayed()));
    }

    @GetMapping("/{requestId}")
    public LocationView get(@CurrentClient ApiClient client,
                            @PathVariable("requestId") UUID requestId) {
        return LocationView.of(requests.get(client, requestId), null);
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
            Boolean replayed) {

        static LocationView of(LocationRequestRepository.StoredRequest stored, Boolean replayed) {
            return new LocationView(stored.requestId(), stored.state(), stored.purpose(),
                    stored.priority(), stored.accuracyRequestedM(), stored.errorCode(),
                    stored.errorDetail(), stored.createdAt(), stored.updatedAt(),
                    stored.deadlineAt(), stored.completedAt(), replayed);
        }
    }
}
