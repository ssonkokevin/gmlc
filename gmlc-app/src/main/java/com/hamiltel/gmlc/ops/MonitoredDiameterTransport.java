package com.hamiltel.gmlc.ops;

import com.hamiltel.gmlc.diameter.DiameterMessage;
import com.hamiltel.gmlc.diameter.DiameterTransport;
import com.hamiltel.gmlc.diameter.SignallingException;

import java.time.Duration;

/**
 * Records the outcome of every Diameter exchange for the console's peer panel.
 * A diameter answer with an error result code is still a successful exchange:
 * this measures the link, not the query.
 */
public final class MonitoredDiameterTransport implements DiameterTransport {

    private final DiameterTransport delegate;
    private final PeerHealth health;

    public MonitoredDiameterTransport(DiameterTransport delegate, PeerHealth health) {
        this.delegate = delegate;
        this.health = health;
    }

    @Override
    public DiameterMessage exchange(DiameterMessage request, Duration timeout) {
        try {
            DiameterMessage answer = delegate.exchange(request, timeout);
            health.success(PeerHealth.DIAMETER);
            return answer;
        } catch (SignallingException e) {
            health.failure(PeerHealth.DIAMETER, e.code());
            throw e;
        } catch (RuntimeException e) {
            health.failure(PeerHealth.DIAMETER, e.getClass().getSimpleName());
            throw e;
        }
    }

    @Override
    public void close() {
        delegate.close();
    }
}
