package com.hamiltel.gmlc.cell;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Checks one cell before it can enter the authoritative database. A cell
 * without coordinates or without a coverage radius is refused: the first
 * cannot be located, and the second cannot bound an uncertainty, so a result
 * built on it would be a guess dressed as a measurement.
 */
public final class CellValidator {

    public static final Set<String> CELL_TYPES = Set.of("MACRO", "MICRO", "INDOOR", "UNKNOWN");

    private static final BigDecimal MAX_LAT = new BigDecimal("90");
    private static final BigDecimal MAX_LON = new BigDecimal("180");
    private static final int MAX_RADIUS_M = 200_000;
    private static final int MAX_ARFCN = 3_279_165;

    private CellValidator() {
    }

    /** @return field name to message, empty when the cell is acceptable */
    public static Map<String, String> validate(CellInput input) {
        Map<String, String> errors = new LinkedHashMap<>();

        CellIdentity.Rat rat = parseRat(input.rat(), errors);
        requireDigits(errors, "plmnMcc", input.plmnMcc(), 3, 3, "must be a 3-digit MCC");
        requireDigits(errors, "plmnMnc", input.plmnMnc(), 2, 3, "must be a 2- or 3-digit MNC");
        Long area = requireDigits(errors, "areaCode", input.areaCode(), 1, 5,
                "must be a decimal LAC/TAC");
        if (area != null && area > 65_535) {
            errors.put("areaCode", "must fit in 16 bits (0-65535)");
        }
        Long cell = requireDigits(errors, "cellCode", input.cellCode(), 1, 12,
                "must be a decimal cell identity");
        if (cell != null && rat != null && cell > rat.maxCellCode()) {
            errors.put("cellCode", "must fit " + rat + " (" + rat.areaName() + " area, cell <= "
                    + rat.maxCellCode() + ")");
        }

        checkCoordinate(errors, "latitude", input.latitude(), MAX_LAT);
        checkCoordinate(errors, "longitude", input.longitude(), MAX_LON);
        if (input.latitude() == null && input.longitude() == null) {
            errors.put("latitude", "antenna coordinates are required");
            errors.put("longitude", "antenna coordinates are required");
        }

        if (input.radiusM() == null || input.radiusM() < 1) {
            errors.put("radiusM", "a positive coverage radius in metres is required");
        } else if (input.radiusM() > MAX_RADIUS_M) {
            errors.put("radiusM", "must be at most " + MAX_RADIUS_M + " m");
        }
        if (input.antennaHeightM() != null && (input.antennaHeightM().signum() < 0
                || input.antennaHeightM().compareTo(new BigDecimal("300")) > 0)) {
            errors.put("antennaHeightM", "must be between 0 and 300 m");
        }
        if (input.azimuthDeg() != null && (input.azimuthDeg() < 0 || input.azimuthDeg() > 359)) {
            errors.put("azimuthDeg", "must be between 0 and 359");
        }
        if (input.beamwidthDeg() != null
                && (input.beamwidthDeg() < 1 || input.beamwidthDeg() > 360)) {
            errors.put("beamwidthDeg", "must be between 1 and 360");
        }
        if (input.pci() != null && (input.pci() < 0 || input.pci() > 1007)) {
            errors.put("pci", "must be between 0 and 1007");
        }
        if (input.arfcn() != null && (input.arfcn() < 0 || input.arfcn() > MAX_ARFCN)) {
            errors.put("arfcn", "must be between 0 and " + MAX_ARFCN);
        }
        if (input.cellType() != null && !CELL_TYPES.contains(normalise(input.cellType()))) {
            errors.put("cellType", "must be one of MACRO, MICRO, INDOOR, UNKNOWN");
        }
        if (input.siteName() != null && input.siteName().length() > 128) {
            errors.put("siteName", "must be at most 128 characters");
        }
        if (input.dataSource() != null && input.dataSource().length() > 64) {
            errors.put("dataSource", "must be at most 64 characters");
        }
        if (input.dataQuality() != null && (input.dataQuality() < 0 || input.dataQuality() > 100)) {
            errors.put("dataQuality", "must be between 0 and 100");
        }
        if (input.lastVerified() != null
                && input.lastVerified().isAfter(LocalDate.now(ZoneOffset.UTC))) {
            errors.put("lastVerified", "must not be in the future");
        }
        return errors;
    }

    public static void check(CellInput input) {
        Map<String, String> errors = validate(input);
        if (!errors.isEmpty()) {
            throw new InvalidCellException(errors);
        }
    }

    /**
     * The validated input with conventional spelling: upper-case RAT, no
     * leading zeros in the decimal part. Validation runs against the original,
     * so this never relaxes a check.
     */
    public static CellInput canonical(CellInput input) {
        return new CellInput(
                input.rat() == null ? null : input.rat().trim().toUpperCase(Locale.ROOT),
                input.plmnMcc(), input.plmnMnc(),
                CellIdentity.canonicalNumber(input.areaCode()),
                CellIdentity.canonicalNumber(input.cellCode()),
                input.siteName(), input.latitude(), input.longitude(),
                input.antennaHeightM(), input.azimuthDeg(), input.beamwidthDeg(),
                input.radiusM(), input.pci(), input.arfcn(), input.cellType(),
                input.dataSource(), input.dataQuality(), input.lastVerified());
    }

    public static CellIdentity.Rat rat(CellInput input) {
        return CellIdentity.Rat.of(input.rat());
    }

    public static String normalise(String cellType) {
        return cellType == null || cellType.isBlank()
                ? "UNKNOWN"
                : cellType.trim().toUpperCase(Locale.ROOT);
    }

    private static CellIdentity.Rat parseRat(String value, Map<String, String> errors) {
        if (value == null || value.isBlank()) {
            errors.put("rat", "must be one of GSM, UMTS, LTE, NR");
            return null;
        }
        try {
            return CellIdentity.Rat.of(value);
        } catch (IllegalArgumentException e) {
            errors.put("rat", "must be one of GSM, UMTS, LTE, NR");
            return null;
        }
    }

    private static Long requireDigits(Map<String, String> errors, String field, String value,
                                      int min, int max, String message) {
        if (value == null || value.isBlank() || !value.matches("[0-9]{" + min + "," + max + "}")) {
            errors.put(field, message);
            return null;
        }
        return Long.parseLong(value);
    }

    private static void checkCoordinate(Map<String, String> errors, String field,
                                        BigDecimal value, BigDecimal limit) {
        if (value == null) {
            return;
        }
        if (value.abs().compareTo(limit) > 0) {
            errors.put(field, "must be between -" + limit.toPlainString()
                    + " and " + limit.toPlainString());
        }
    }
}
