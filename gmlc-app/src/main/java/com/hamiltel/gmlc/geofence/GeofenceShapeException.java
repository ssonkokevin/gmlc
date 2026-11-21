package com.hamiltel.gmlc.geofence;

/**
 * A shape that cannot be stored because it breaks a TS 23.032 limit or is
 * geographically ambiguous. The code travels to the operator unchanged so a
 * form can point at the field that was refused.
 */
public class GeofenceShapeException extends RuntimeException {

    private final String code;

    public GeofenceShapeException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
