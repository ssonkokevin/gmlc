package com.hamiltel.gmlc.capability;

/**
 * A stored capability profile exists but does not satisfy the schema. The API
 * fails closed: serving it would let a client believe a method is available.
 */
public class CapabilityProfileUnavailableException extends RuntimeException {

    public CapabilityProfileUnavailableException(String message) {
        super(message);
    }

    public CapabilityProfileUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
