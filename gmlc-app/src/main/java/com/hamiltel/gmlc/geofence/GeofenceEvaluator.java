package com.hamiltel.gmlc.geofence;

import com.hamiltel.gmlc.location.LocationEstimate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Evaluates stored results against the arms of one subject. It runs after the
 * result row exists, inside its own transaction, and never changes the answer
 * the client receives: a geofence defect is logged and the location stands.
 *
 * <p>Only results the gateway already stored are evaluated. There is no
 * network-initiated area-event subscription here, so no event is claimed that
 * did not come from a governed request.
 */
@Service
public class GeofenceEvaluator {

    private static final Logger log = LoggerFactory.getLogger(GeofenceEvaluator.class);

    private final GeofenceRepository geofences;

    public GeofenceEvaluator(GeofenceRepository geofences) {
        this.geofences = geofences;
    }

    @Transactional
    public void evaluate(long tenantId, String subjectRef, UUID requestId, UUID resultId,
                         LocationEstimate estimate) {
        if (estimate.latitude() == null || estimate.longitude() == null) {
            return;
        }
        double latitude = estimate.latitude().doubleValue();
        double longitude = estimate.longitude().doubleValue();
        for (GeofenceRepository.ArmedGeofence armed
                : geofences.armedGeofences(tenantId, subjectRef)) {
            try {
                GeofenceGeometry geometry = GeofenceGeometry.parse(armed.geometry());
                boolean inside = geometry.contains(latitude, longitude);
                geofences.recordEvaluation(tenantId, armed.geofenceId(), subjectRef,
                        armed.subjectLabel(), armed.state(), inside, armed.reportInside(),
                        resultId, requestId, estimate.producedAt(), latitude, longitude,
                        estimate.uncertaintyM(), estimate.method());
            } catch (RuntimeException e) {
                log.warn("geofence {} could not be evaluated for request {}: {}",
                        armed.geofenceId(), requestId, e.getClass().getSimpleName());
            }
        }
    }
}
