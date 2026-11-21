package com.hamiltel.gmlc.ss7;

import com.hamiltel.gmlc.map.MapException;

import java.time.Duration;

/**
 * One M3UA association towards a signalling gateway. An exchange sends one
 * SCCP unitdata message and returns the next SCCP unitdata that arrives. The
 * unitdata carries no correlation identifier, so matching an answer to its
 * dialogue is the TCAP layer's job; this transport only refuses to run when it
 * is not configured.
 */
public interface M3uaTransport extends AutoCloseable {

    Sccp.UnitData exchange(int destinationPointCode, Sccp.UnitData request, Duration timeout);

    @Override
    default void close() {
    }

    static M3uaTransport disabled(String reason) {
        return (destinationPointCode, request, timeout) -> {
            throw new MapException("SIGNALLING_NOT_CONFIGURED", reason);
        };
    }
}
