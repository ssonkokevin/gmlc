package com.hamiltel.gmlc.ops;

import java.time.Instant;

/** The account is in its lockout window; the response says for how long. */
public class OpsLockedException extends RuntimeException {

    private final Instant lockedUntil;

    public OpsLockedException(Instant lockedUntil) {
        this.lockedUntil = lockedUntil;
    }

    public Instant lockedUntil() {
        return lockedUntil;
    }
}
