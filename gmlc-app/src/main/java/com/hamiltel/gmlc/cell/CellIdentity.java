package com.hamiltel.gmlc.cell;

import java.util.Locale;

/**
 * Canonical cell identities in the form the core repository uses:
 * {@code mcc-mnc-area-cell} with the area being LAC (GSM/UMTS) or TAC
 * (LTE/NR).
 *
 * <p>PROVISIONAL: bit widths come from the field definitions in TS 25.413,
 * TS 36.413 and TS 38.413. Re-verify against TS 23.003 before Phase 2 exit
 * (decision D15).
 */
public final class CellIdentity {

    public enum Rat {
        GSM,
        UMTS,
        LTE,
        NR;

        public static Rat of(String value) {
            if (value == null) {
                throw new IllegalArgumentException("rat is required");
            }
            return Rat.valueOf(value.trim().toUpperCase(Locale.ROOT));
        }

        /** C-ID / ECI / NCI ceiling. GSM CI is 16 bits, UMTS C-ID 28, LTE 28, NR 36. */
        public long maxCellCode() {
            return switch (this) {
                case GSM -> 65_535L;
                case UMTS, LTE -> 268_435_455L;
                case NR -> 68_719_476_735L;
            };
        }

        public String areaName() {
            return this == LTE || this == NR ? "TAC" : "LAC";
        }
    }

    public record Parts(Rat rat, String mcc, String mnc, String areaCode, String cellCode,
                        String identity) {
    }

    private CellIdentity() {
    }

    /**
     * Composes the canonical string. Decimal parts lose leading zeros, so
     * {@code 04660} and {@code 4660} name the same cell however the operator
     * typed it.
     */
    public static String compose(Rat rat, String mcc, String mnc, String areaCode, String cellCode) {
        return mcc + "-" + mnc + "-" + canonicalNumber(areaCode) + "-" + canonicalNumber(cellCode);
    }

    public static String canonicalNumber(String digits) {
        return digits == null ? null : digits.replaceFirst("^0+(?=.)", "");
    }

    /**
     * Splits a canonical identity. Hyphens separate all four parts, so the
     * MNC's 2-or-3 digit length is never ambiguous.
     */
    public static Parts parse(Rat rat, String identity) {
        String[] parts = identity == null ? new String[0] : identity.split("-");
        if (parts.length != 4) {
            throw new IllegalArgumentException(
                    "identity must be mcc-mnc-area-cell, for example 001-01-4660-1234");
        }
        return new Parts(rat, parts[0], parts[1], parts[2], parts[3], identity);
    }

    /** PLMN as {@code mcc-mnc}, used in logs and views. */
    public static String plmn(String mcc, String mnc) {
        return mcc + "-" + mnc;
    }
}
