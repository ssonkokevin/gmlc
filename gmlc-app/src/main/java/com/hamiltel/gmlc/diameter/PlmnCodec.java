package com.hamiltel.gmlc.diameter;

/**
 * PLMN identity in the three-octet form of TS 29.274 clause 8.21.5, which is
 * what the ECGI and CGI carried by SLg use: octet 1 is MCC digit 2 over digit
 * 1, octet 2 is MNC digit 3 over MCC digit 3, octet 3 is MNC digit 2 over digit
 * 1. An MCC is always three digits; a two-digit MNC fills digit 3 with 0xF.
 * Note this is not the nibble order of the TS 24.008 LAI.
 */
public final class PlmnCodec {

    private PlmnCodec() {
    }

    public record Plmn(String mcc, String mnc) {
        public Plmn {
            if (mcc == null || !mcc.matches("[0-9]{3}")) {
                throw new IllegalArgumentException("MCC must be three digits: " + mcc);
            }
            if (mnc == null || !mnc.matches("[0-9]{2,3}")) {
                throw new IllegalArgumentException("MNC must be two or three digits: " + mnc);
            }
        }
    }

    public static byte[] encode(String mcc, String mnc) {
        Plmn plmn = new Plmn(mcc, mnc);
        int mcc2 = mcc.charAt(1) - '0';
        int mcc1 = mcc.charAt(0) - '0';
        int mcc3 = mcc.charAt(2) - '0';
        int mnc3 = mnc.length() == 3 ? mnc.charAt(2) - '0' : 0xF;
        int mnc2 = mnc.charAt(1) - '0';
        int mnc1 = mnc.charAt(0) - '0';
        return new byte[] {
                (byte) ((mcc2 << 4) | mcc1),
                (byte) ((mnc3 << 4) | mcc3),
                (byte) ((mnc2 << 4) | mnc1) };
    }

    public static Plmn decode(byte[] three) {
        if (three == null || three.length < 3) {
            throw new IllegalArgumentException("a PLMN identity is three octets");
        }
        int o1 = three[0] & 0xFF;
        int o2 = three[1] & 0xFF;
        int o3 = three[2] & 0xFF;
        int mcc1 = o1 & 0x0F;
        int mcc2 = (o1 >>> 4) & 0x0F;
        int mcc3 = o2 & 0x0F;
        int mnc3 = (o2 >>> 4) & 0x0F;
        int mnc1 = o3 & 0x0F;
        int mnc2 = (o3 >>> 4) & 0x0F;
        String mcc = "" + digit(mcc1) + digit(mcc2) + digit(mcc3);
        String mnc = mnc3 == 0xF ? "" + digit(mnc1) + digit(mnc2)
                : "" + digit(mnc1) + digit(mnc2) + digit(mnc3);
        return new Plmn(mcc, mnc);
    }

    private static char digit(int nibble) {
        if (nibble > 9) {
            throw new IllegalArgumentException("PLMN nibble " + nibble + " is not a decimal digit");
        }
        return (char) ('0' + nibble);
    }
}
