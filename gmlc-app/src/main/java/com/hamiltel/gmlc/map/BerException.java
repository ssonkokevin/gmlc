package com.hamiltel.gmlc.map;

/** A BER element that cannot be read as the spec requires. */
public class BerException extends RuntimeException {

    public BerException(String message) {
        super(message);
    }
}
