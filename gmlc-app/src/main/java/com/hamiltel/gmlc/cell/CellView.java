package com.hamiltel.gmlc.cell;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * One cell as the API shows it. The shape and ring are derived from the
 * stored plan so a reader sees the geometry a result would use, and the data
 * quality says how much that geometry is worth.
 */
public record CellView(
        String rat,
        String cellIdentity,
        String plmn,
        String areaCode,
        String cellCode,
        String siteName,
        BigDecimal latitude,
        BigDecimal longitude,
        BigDecimal antennaHeightM,
        Integer azimuthDeg,
        Integer beamwidthDeg,
        Integer radiusM,
        String shape,
        JsonNode coveragePolygon,
        Integer pci,
        Integer arfcn,
        String cellType,
        String dataSource,
        Integer dataQuality,
        LocalDate lastVerified,
        Instant updatedAt) {

    public static CellView of(CellRepository.CellRow row, ObjectMapper mapper) {
        return new CellView(
                row.rat(), row.cellIdentity(),
                CellIdentity.plmn(row.plmnMcc(), row.plmnMnc()),
                row.areaCode(), row.cellCode(), row.siteName(),
                row.latitude(), row.longitude(), row.antennaHeightM(),
                row.azimuthDeg(), row.beamwidthDeg(), row.radiusM(),
                CellGeometry.shapeOf(row.radiusM(), row.azimuthDeg(), row.beamwidthDeg()).value(),
                polygon(row, mapper),
                row.pci(), row.arfcn(), row.cellType(), row.dataSource(), row.dataQuality(),
                row.lastVerified(), row.updatedAt());
    }

    /** The list view leaves the ring out; a page of 100 sectors is mostly geometry. */
    public static CellSummary summary(CellRepository.CellRow row) {
        return new CellSummary(row.rat(), row.cellIdentity(), row.areaCode(), row.siteName(),
                row.latitude(), row.longitude(),
                CellGeometry.shapeOf(row.radiusM(), row.azimuthDeg(), row.beamwidthDeg()).value(),
                row.radiusM(), row.dataQuality(), row.updatedAt());
    }

    public record CellSummary(
            String rat,
            String cellIdentity,
            String areaCode,
            String siteName,
            BigDecimal latitude,
            BigDecimal longitude,
            String shape,
            Integer radiusM,
            Integer dataQuality,
            Instant updatedAt) {
    }

    private static JsonNode polygon(CellRepository.CellRow row, ObjectMapper mapper) {
        if (row.coveragePolygon() != null) {
            try {
                return mapper.readTree(row.coveragePolygon());
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("stored coverage polygon is not valid JSON", e);
            }
        }
        if (row.latitude() == null || row.longitude() == null || row.radiusM() == null) {
            return null;
        }
        List<List<BigDecimal>> ring = CellGeometry.ringOf(row.latitude().doubleValue(),
                row.longitude().doubleValue(), row.radiusM(), row.azimuthDeg(), row.beamwidthDeg());
        return mapper.valueToTree(java.util.Map.of("type", "Polygon", "coordinates", List.of(ring)));
    }
}
