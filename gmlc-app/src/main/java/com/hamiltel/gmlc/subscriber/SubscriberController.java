package com.hamiltel.gmlc.subscriber;

import com.hamiltel.gmlc.client.ApiClient;
import com.hamiltel.gmlc.client.CurrentClient;
import com.hamiltel.gmlc.config.OpsProperties;
import com.hamiltel.gmlc.tracking.TrackingRepository;
import com.hamiltel.gmlc.tracking.TrackingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The client's own view of a subject it tracks.
 *
 * <p>Only the client that added the subject can read it; another client's
 * identifier is a 404 with no hint. The answer carries the fix's full honesty
 * envelope - method, uncertainty, confidence, age - or, when there is nothing
 * to show, a stable reason. Consent is checked on every read, so a revocation
 * is visible here even while the roster row remains.
 *
 * <p>{@code dataSource} is the deployment's label: a lab deployment answers
 * LAB_SIM and can never present a lab fix as a real one.
 */
@RestController
@RequestMapping("/v1/subscribers")
public class SubscriberController {

    private final TrackingService tracking;
    private final OpsProperties properties;

    public SubscriberController(TrackingService tracking, OpsProperties properties) {
        this.tracking = tracking;
        this.properties = properties;
    }

    @GetMapping("/{trackedId}/last-known")
    public LastKnownView lastKnown(@CurrentClient ApiClient client,
                                   @PathVariable UUID trackedId) {
        return LastKnownView.of(tracking.lastKnown(client.tenantId(), client.clientId(), trackedId),
                properties.dataSourceLabel());
    }

    public record LastKnownView(boolean found, String reason, UUID trackedId, UUID clientId,
                                String purpose, String dataSource, Fix fix) {

        static LastKnownView of(TrackingService.LastKnown answer, String dataSource) {
            return new LastKnownView(answer.found(), answer.reason(), answer.trackedId(),
                    answer.clientId(), answer.purpose(), dataSource,
                    answer.fix() == null ? null : Fix.of(answer.fix()));
        }
    }

    public record Fix(UUID requestId, UUID resultId, String method, List<String> methodsAttempted,
                      String shape, BigDecimal latitude, BigDecimal longitude,
                      Integer uncertaintyM, Integer confidence, Integer ageSeconds,
                      Integer accuracyRequestedM, Boolean accuracyFulfilled, String servingCell,
                      String engine, String positioningNode, Instant producedAt) {

        static Fix of(TrackingRepository.FixView stored) {
            return new Fix(stored.requestId(), stored.resultId(), stored.method(),
                    stored.methodsAttempted(), stored.shape(), stored.latitude(),
                    stored.longitude(), stored.uncertaintyM(), stored.confidence(),
                    stored.ageSeconds(), stored.accuracyRequestedM(), stored.accuracyFulfilled(),
                    stored.servingCell(), stored.engine(), stored.positioningNode(),
                    stored.producedAt());
        }
    }
}
