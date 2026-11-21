package com.hamiltel.gmlc.ops;

/** A change would leave the console without an administrator or lock the caller out. */
public class OpsConflictException extends RuntimeException {

    private final String code;

    public OpsConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
