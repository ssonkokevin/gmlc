package com.hamiltel.gmlc.location;

import com.hamiltel.gmlc.diameter.SignallingException;

import java.util.Optional;

/**
 * Turns a subscriber into a position. An empty answer means the network
 * answered but named no cell - a result the gateway reports as no source
 * rather than inventing one. A signalling failure throws, and its code is
 * what the requester sees.
 */
public interface LocationLocator {

    Optional<LocationEstimate> locate(Query query) throws SignallingException;

    record Query(long tenantId, String msisdn, String imsi, Integer accuracyMeters,
                 String purpose, String lcsClientName) {

        public boolean emergency() {
            return "EMERGENCY".equalsIgnoreCase(purpose);
        }
    }
}
