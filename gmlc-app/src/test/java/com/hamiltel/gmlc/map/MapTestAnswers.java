package com.hamiltel.gmlc.map;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Answer payloads as an HLR, MSC, SGSN or MME would put them on the wire. */
final class MapTestAnswers {

    static final String MSISDN = "256772000001";
    static final String IMSI = "001010000000001";
    static final byte[] MSISDN_ADDRESS = MapLcs.address(MSISDN);

    /** 001-01, LAC 4660, CI 1234. */
    static final byte[] GSM_CGI = bytes(0x00, 0xF1, 0x10, 0x12, 0x34, 0x04, 0xD2);
    /** 001-01, ECI 662273. */
    static final byte[] LTE_CGI = bytes(0x00, 0xF1, 0x10, 0x00, 0x0A, 0x1B, 0x01);
    /** 001-01, TAC 1. */
    static final byte[] LTE_TAI = bytes(0x00, 0xF1, 0x10, 0x00, 0x01);

    private MapTestAnswers() {
    }

    static byte[] routing(String networkNode, boolean gprs, String mmeName) {
        List<byte[]> parts = new ArrayList<>();
        if (networkNode != null) {
            parts.add(Ber.octets(MapLcs.address(networkNode)));
        }
        if (gprs) {
            parts.add(Ber.nullValue(Ber.context(2)));
        }
        if (mmeName != null) {
            parts.add(Ber.tlv(Ber.context(6), mmeName.getBytes(StandardCharsets.US_ASCII)));
        }
        return Ber.sequence(
                Ber.tlv(Ber.contextConstructed(0),
                        Ber.tlv(Ber.context(1), MSISDN_ADDRESS)),
                Ber.tlv(Ber.contextConstructed(1),
                        Ber.concat(parts.toArray(new byte[0][]))));
    }

    static byte[] provideLocation(Integer ageMinutes, byte[] cgi) {
        List<byte[]> parts = new ArrayList<>();
        parts.add(Ber.octets(Gad.encodeCircle(0.3136, 32.5811, 20)));
        if (ageMinutes != null) {
            parts.add(Ber.taggedInteger(Ber.context(0), ageMinutes));
        }
        if (cgi != null) {
            parts.add(Ber.tlv(Ber.contextConstructed(6), Ber.tlv(Ber.context(0), cgi)));
        }
        return Ber.sequence(parts.toArray(new byte[0][]));
    }

    static byte[] anyTimeEps(int ageMinutes, byte[] cgi, byte[] trackingAreaId) {
        return Ber.sequence(Ber.sequence(
                Ber.tlv(Ber.contextConstructed(13), Ber.concat(
                        Ber.tlv(Ber.context(0), cgi),
                        Ber.tlv(Ber.context(1), trackingAreaId),
                        Ber.taggedInteger(Ber.context(6), ageMinutes)))));
    }

    static byte[] bytes(int... values) {
        byte[] encoded = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            encoded[i] = (byte) values[i];
        }
        return encoded;
    }
}
