package com.hamiltel.gmlc.location;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * A position with its honesty envelope. Every field that qualifies the answer
 * travels with it: the method that produced it, the radius that bounds it, the
 * confidence in the source, and how old the network said it was.
 *
 * @param shape       always {@code CIRCLE} for a cell-ID answer
 * @param methodsAttempted the signalling that was tried, so a reader can see
 *                        how the cell was obtained
 */
public record LocationEstimate(
        String rat,
        String method,
        String shape,
        BigDecimal latitude,
        BigDecimal longitude,
        int uncertaintyM,
        int confidence,
        Integer geometryDataQuality,
        int ageSeconds,
        boolean accuracyFulfilled,
        Integer accuracyRequestedM,
        String servingCell,
        String engine,
        String positioningNode,
        Instant producedAt,
        List<String> methodsAttempted) {
}
