package com.hamiltel.gmlc.cell;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One cell, from JSON or one CSV row. The identity is derived from the parts
 * rather than accepted as a string, so two importers cannot disagree on how a
 * cell is named.
 */
public record CellInput(
        String rat,
        String plmnMcc,
        String plmnMnc,
        String areaCode,
        String cellCode,
        String siteName,
        BigDecimal latitude,
        BigDecimal longitude,
        BigDecimal antennaHeightM,
        Integer azimuthDeg,
        Integer beamwidthDeg,
        Integer radiusM,
        Integer pci,
        Integer arfcn,
        String cellType,
        String dataSource,
        Integer dataQuality,
        LocalDate lastVerified) {
}
