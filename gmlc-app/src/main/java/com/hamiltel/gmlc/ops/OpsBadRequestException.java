package com.hamiltel.gmlc.ops;

/** A rejected query parameter, answered as 400 with a stable code. */
public class OpsBadRequestException extends RuntimeException {

    private final String code;

    public OpsBadRequestException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
