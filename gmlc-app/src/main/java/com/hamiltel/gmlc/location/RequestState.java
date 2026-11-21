package com.hamiltel.gmlc.location;

import java.util.Set;

/**
 * The request state machine. Transitions are checked rather than assumed: a
 * row only moves along an edge declared here, so a late answer cannot revive a
 * request that already failed, and a request cannot be delivered twice.
 */
public enum RequestState {

    RECEIVED,
    AUTHORISED,
    PRIVACY_CHECKED,
    ROUTED,
    REQUESTED,
    ANSWERED,
    TIMEOUT,
    FAILED,
    DELIVERED;

    public boolean canMoveTo(RequestState next) {
        return switch (this) {
            case RECEIVED -> next == AUTHORISED || failure(next);
            case AUTHORISED -> next == PRIVACY_CHECKED || failure(next);
            case PRIVACY_CHECKED -> next == ROUTED || failure(next);
            case ROUTED -> next == REQUESTED || next == ANSWERED || failure(next);
            case REQUESTED -> next == ANSWERED || next == TIMEOUT || next == FAILED;
            case ANSWERED -> next == DELIVERED;
            case TIMEOUT, FAILED, DELIVERED -> false;
        };
    }

    public boolean isFinal() {
        return this == TIMEOUT || this == FAILED || this == DELIVERED;
    }

    private static boolean failure(RequestState next) {
        return Set.of(TIMEOUT, FAILED).contains(next);
    }
}
