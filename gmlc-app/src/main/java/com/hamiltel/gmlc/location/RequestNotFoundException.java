package com.hamiltel.gmlc.location;

import java.util.UUID;

/** Unknown, or known to another tenant or client: both answer 404. */
public class RequestNotFoundException extends RuntimeException {

    private final UUID requestId;

    public RequestNotFoundException(UUID requestId) {
        super("no such location request");
        this.requestId = requestId;
    }

    public UUID requestId() {
        return requestId;
    }
}
