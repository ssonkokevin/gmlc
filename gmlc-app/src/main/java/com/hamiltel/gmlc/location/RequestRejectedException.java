package com.hamiltel.gmlc.location;

/**
 * The request is well-formed JSON but the gateway will not accept it: an
 * identity the tenant's PLMNs do not contain, a purpose this client may not
 * use, or an accuracy beyond its entitlement.
 */
public class RequestRejectedException extends RuntimeException {

    private final String code;

    public RequestRejectedException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
