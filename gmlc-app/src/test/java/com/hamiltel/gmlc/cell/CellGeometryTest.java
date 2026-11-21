package com.hamiltel.gmlc.cell;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CellGeometryTest {

    private static final double LAT = 0.313611;
    private static final double LON = 32.581111;

    @Test
    void aCircleRingIsClosedAndEveryVertexIsOnTheRadius() {
        List<List<BigDecimal>> ring = CellGeometry.circle(LAT, LON, 250);

        assertThat(ring).hasSizeGreaterThan(8);
        assertThat(ring.get(0)).isEqualTo(ring.get(ring.size() - 1));
        for (List<BigDecimal> vertex : ring) {
            double metres = CellGeometry.distanceMetres(LAT, LON,
                    vertex.get(1).doubleValue(), vertex.get(0).doubleValue());
            assertThat(metres).isBetween(249.0, 251.0);
        }
    }

    @Test
    void aSectorStaysInsideItsBeamAndStartsAtTheAntenna() {
        int azimuth = 90;
        int beamwidth = 60;
        List<List<BigDecimal>> ring = CellGeometry.sector(LAT, LON, azimuth, beamwidth, 1000);

        assertThat(ring.get(0).get(0).doubleValue()).isEqualTo(32.5811110);
        assertThat(ring.get(0).get(1).doubleValue()).isEqualTo(0.3136110);
        for (int i = 1; i < ring.size() - 1; i++) {
            List<BigDecimal> vertex = ring.get(i);
            double metres = CellGeometry.distanceMetres(LAT, LON,
                    vertex.get(1).doubleValue(), vertex.get(0).doubleValue());
            assertThat(metres).isBetween(999.0, 1001.0);
            assertThat(offsetFrom(azimuth, bearing(LAT, LON,
                    vertex.get(1).doubleValue(), vertex.get(0).doubleValue())))
                    .isBetween(-30.5, 30.5);
        }
    }

    @Test
    void shapeReflectsWhatIsKnownAboutTheCell() {
        assertThat(CellGeometry.shapeOf(null, 120, 65)).isEqualTo(CellGeometry.Shape.POINT);
        assertThat(CellGeometry.shapeOf(250, null, null)).isEqualTo(CellGeometry.Shape.CIRCLE);
        assertThat(CellGeometry.shapeOf(250, 120, null)).isEqualTo(CellGeometry.Shape.CIRCLE);
        assertThat(CellGeometry.shapeOf(250, 120, 65)).isEqualTo(CellGeometry.Shape.ARC);
    }

    @Test
    void aZeroRadiusOrEmptyBeamIsRefusedRatherThanDrawn() {
        assertThatThrownBy(() -> CellGeometry.circle(LAT, LON, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CellGeometry.sector(LAT, LON, 0, 0, 100))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theDistanceUsesTheLocalMeanEarthRadius() {
        // Kampala to Entebbe is roughly 32 km.
        double metres = CellGeometry.distanceMetres(0.3136, 32.5811, 0.0512, 32.4435);

        assertThat(metres).isBetween(30_000.0, 34_000.0);
    }

    private static double bearing(double lat1, double lon1, double lat2, double lon2) {
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double dLon = Math.toRadians(lon2 - lon1);
        double y = Math.sin(dLon) * Math.cos(phi2);
        double x = Math.cos(phi1) * Math.sin(phi2) - Math.sin(phi1) * Math.cos(phi2) * Math.cos(dLon);
        return Math.toDegrees(Math.atan2(y, x));
    }

    private static double offsetFrom(double centre, double bearing) {
        double difference = ((bearing - centre) + 540) % 360 - 180;
        return difference;
    }
}
