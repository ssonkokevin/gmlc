package com.hamiltel.gmlc.ops;

/** The sealed emergency code was not the one this deployment accepts. */
public class OpsBreakGlassRejectedException extends RuntimeException {

    public OpsBreakGlassRejectedException() {
        super("the emergency code is not valid for this deployment");
    }
}
