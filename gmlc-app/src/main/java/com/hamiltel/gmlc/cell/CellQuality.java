package com.hamiltel.gmlc.cell;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * A 0-100 score for how much the imported record can be trusted. It is a
 * completeness score, not an accuracy measurement: it says which fields an
 * operator supplied, not whether the antenna is where the plan says.
 *
 * <p>Weights: coordinates 40, radius 20, beam 10, antenna height 5, site name
 * 5, PCI 3, ARFCN 2, data source 5, verified within a year 10.
 */
public final class CellQuality {

    private static final int VERIFIED_WITHIN_DAYS = 365;

    private CellQuality() {
    }

    public static int score(CellInput input) {
        int score = 0;
        if (input.latitude() != null && input.longitude() != null) {
            score += 40;
        }
        if (input.radiusM() != null) {
            score += 20;
        }
        if (input.azimuthDeg() != null && input.beamwidthDeg() != null) {
            score += 10;
        }
        if (input.antennaHeightM() != null) {
            score += 5;
        }
        if (!isBlank(input.siteName())) {
            score += 5;
        }
        if (input.pci() != null) {
            score += 3;
        }
        if (input.arfcn() != null) {
            score += 2;
        }
        if (!isBlank(input.dataSource())) {
            score += 5;
        }
        if (input.lastVerified() != null) {
            long age = ChronoUnit.DAYS.between(input.lastVerified(), LocalDate.now(ZoneOffset.UTC));
            if (age >= 0 && age <= VERIFIED_WITHIN_DAYS) {
                score += 10;
            }
        }
        return Math.min(100, score);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
