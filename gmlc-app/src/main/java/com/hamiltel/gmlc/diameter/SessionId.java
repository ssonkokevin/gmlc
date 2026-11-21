package com.hamiltel.gmlc.diameter;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/** Session-Id values in the RFC 6733 recommended printable form. */
final class SessionId {

    private static final AtomicLong SEQUENCE = new AtomicLong();

    private SessionId() {
    }

    static String next(String originHost) {
        return originHost + ";" + Instant.now().getEpochSecond() + ";" + SEQUENCE.incrementAndGet();
    }
}
