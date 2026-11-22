package com.hamiltel.gmlc.diameter;

import java.io.ByteArrayOutputStream;

/**
 * TBCD digit strings as used by the MSISDN AVP and by MAP. The first digit
 * occupies the low nibble of the first octet; an odd count is padded with 0xF.
 */
public final class Tbcd {

    private Tbcd() {
    }

    public static byte[] encode(String digits) {
        if (digits == null || digits.isEmpty()) {
            throw new IllegalArgumentException("an empty digit string has no TBCD form");
        }
        if (!digits.chars().allMatch(c -> c >= '0' && c <= '9')) {
            throw new IllegalArgumentException("TBCD holds decimal digits only");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream((digits.length() + 1) / 2);
        for (int i = 0; i < digits.length(); i += 2) {
            int low = digits.charAt(i) - '0';
            int high = i + 1 < digits.length() ? digits.charAt(i + 1) - '0' : 0xF;
            out.write((high << 4) | low);
        }
        return out.toByteArray();
    }

    public static String decode(byte[] bytes) {
        StringBuilder digits = new StringBuilder(bytes.length * 2);
        for (int index = 0; index < bytes.length; index++) {
            int low = bytes[index] & 0x0F;
            int high = (bytes[index] >>> 4) & 0x0F;
            if (low > 9) {
                throw new IllegalArgumentException(
                        "TBCD octet " + index + " has no digit in its low nibble (" + low + ")");
            }
            digits.append((char) ('0' + low));
            if (high == 0xF) {
                if (index != bytes.length - 1) {
                    throw new IllegalArgumentException(
                            "TBCD filler at octet " + index + ", not the last octet");
                }
                break;
            }
            if (high > 9) {
                throw new IllegalArgumentException(
                        "TBCD octet " + index + " has no digit in its high nibble (" + high + ")");
            }
            digits.append((char) ('0' + high));
        }
        return digits.toString();
    }
}
