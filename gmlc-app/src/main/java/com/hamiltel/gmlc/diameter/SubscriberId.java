package com.hamiltel.gmlc.diameter;

/**
 * The subscriber as signalling needs it: an IMSI, an MSISDN, or both. This is
 * the raw identity, so it stays inside the signalling layer and never reaches
 * a response or an audit row.
 */
public record SubscriberId(String imsi, String msisdn) {

    public SubscriberId {
        if ((imsi == null || imsi.isBlank()) && (msisdn == null || msisdn.isBlank())) {
            throw new IllegalArgumentException("a subscriber needs an IMSI or an MSISDN");
        }
        if (imsi != null && !imsi.matches("[0-9]{5,15}")) {
            throw new IllegalArgumentException("IMSI must be 5 to 15 digits");
        }
        if (msisdn != null && !msisdn.matches("[0-9]{5,20}")) {
            throw new IllegalArgumentException("MSISDN must be 5 to 20 digits");
        }
        imsi = imsi == null || imsi.isBlank() ? null : imsi;
        msisdn = msisdn == null || msisdn.isBlank() ? null : msisdn;
    }

    public boolean hasImsi() {
        return imsi != null;
    }
}
