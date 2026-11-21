package com.hamiltel.gmlc.map;

/** A MAP/TCAP failure with a stable code the location path can report. */
public class MapException extends RuntimeException {

    private final String code;

    public MapException(String code, String message) {
        super(message);
        this.code = code;
    }

    public MapException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
