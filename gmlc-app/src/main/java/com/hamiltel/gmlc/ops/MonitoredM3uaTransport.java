package com.hamiltel.gmlc.ops;

import com.hamiltel.gmlc.map.MapException;
import com.hamiltel.gmlc.ss7.M3uaTransport;
import com.hamiltel.gmlc.ss7.Sccp;

import java.time.Duration;

/**
 * Records the outcome of every M3UA exchange for the console's peer panel. An
 * SCCP answer carrying a MAP error is still a successful exchange; only a
 * transport refusal moves the face to DOWN.
 */
public final class MonitoredM3uaTransport implements M3uaTransport {

    private final M3uaTransport delegate;
    private final PeerHealth health;

    public MonitoredM3uaTransport(M3uaTransport delegate, PeerHealth health) {
        this.delegate = delegate;
        this.health = health;
    }

    @Override
    public Sccp.UnitData exchange(int destinationPointCode, Sccp.UnitData request,
                                  Duration timeout) {
        try {
            Sccp.UnitData answer = delegate.exchange(destinationPointCode, request, timeout);
            health.success(PeerHealth.M3UA);
            return answer;
        } catch (MapException e) {
            health.failure(PeerHealth.M3UA, e.code());
            throw e;
        } catch (RuntimeException e) {
            health.failure(PeerHealth.M3UA, e.getClass().getSimpleName());
            throw e;
        }
    }

    @Override
    public void close() {
        delegate.close();
    }
}
