package com.hamiltel.gmlc.diameter;

import java.time.Duration;

/**
 * Sends one Diameter request and returns its answer. Implementations must
 * either produce an answer or throw {@link SignallingException}; a missing
 * peer must never look like a successful location.
 */
public interface DiameterTransport extends AutoCloseable {

    DiameterMessage exchange(DiameterMessage request, Duration timeout);

    @Override
    default void close() {
    }

    /** A transport for a deployment with no Diameter peer configured. */
    static DiameterTransport disabled(String reason) {
        return (request, timeout) -> {
            throw new SignallingException("SIGNALLING_NOT_CONFIGURED", reason);
        };
    }
}
