package com.hamiltel.gmlc.diameter;

/** A signalling failure with a stable code a caller can map to a request error. */
public class SignallingException extends RuntimeException {

    private final String code;

    public SignallingException(String code, String message) {
        super(message);
        this.code = code;
    }

    public SignallingException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
