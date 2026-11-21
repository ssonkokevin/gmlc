package com.hamiltel.gmlc.location;

/**
 * No consent covers this subject, purpose and client, and no emergency
 * override applies. The response says nothing about whether the subscriber
 * exists.
 */
public class PrivacyNotSatisfiedException extends RuntimeException {

    private final String purpose;

    public PrivacyNotSatisfiedException(String purpose) {
        super("no consent covers this request");
        this.purpose = purpose;
    }

    public String purpose() {
        return purpose;
    }
}
