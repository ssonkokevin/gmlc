package com.hamiltel.gmlc.tracking;

import java.util.UUID;

/** Unknown, or known to another client: both answer 404 on the client surface. */
public class TrackedSubjectNotFoundException extends RuntimeException {

    private final UUID trackedId;

    public TrackedSubjectNotFoundException(UUID trackedId) {
        super("no such tracked subject");
        this.trackedId = trackedId;
    }

    public UUID trackedId() {
        return trackedId;
    }
}
