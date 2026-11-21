package com.hamiltel.gmlc.diameter;

/**
 * The horizontal uncertainty code of TS 23.032 section 2.4. A code c describes
 * a radius r = 10 * (1.1^c - 1) metres; a request for a given accuracy must
 * send the smallest code whose radius is at least that large, so the gateway
 * never claims a tighter figure than the code it sent.
 */
public final class UncertaintyCode {

    private static final int MAX_CODE = 127;

    private UncertaintyCode() {
    }

    public static int codeForMetres(long metres) {
        if (metres <= 0) {
            return 0;
        }
        for (int code = 1; code <= MAX_CODE; code++) {
            if (radiusMetres(code) >= metres) {
                return code;
            }
        }
        throw new IllegalArgumentException("accuracy " + metres + " m is beyond the largest uncertainty code");
    }

    public static long radiusMetres(int code) {
        if (code < 0 || code > MAX_CODE) {
            throw new IllegalArgumentException("uncertainty code out of range: " + code);
        }
        return (long) Math.ceil(10 * (Math.pow(1.1, code) - 1));
    }
}
