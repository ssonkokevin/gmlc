package com.hamiltel.gmlc.client;

/**
 * A credential was presented and refused. The reason is for the audit trail
 * only; the response never says which check failed, because "your key exists
 * but is disabled" is information an attacker can use.
 */
public class ClientAuthenticationException extends RuntimeException {

    public enum Reason {
        MISSING_KEY,
        UNKNOWN_KEY,
        DISABLED,
        TENANT_INACTIVE,
        ADDRESS_NOT_ALLOWED
    }

    private final Reason reason;

    public ClientAuthenticationException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
