package com.hamiltel.gmlc.consent;

import java.util.UUID;

public class ConsentNotFoundException extends RuntimeException {

    private final UUID consentId;

    public ConsentNotFoundException(UUID consentId) {
        super("no such consent");
        this.consentId = consentId;
    }

    public UUID consentId() {
        return consentId;
    }
}
