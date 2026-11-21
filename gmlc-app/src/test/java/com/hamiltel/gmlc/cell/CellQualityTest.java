package com.hamiltel.gmlc.cell;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class CellQualityTest {

    private static CellInput minimal() {
        return new CellInput("LTE", "001", "01", "4660", "1234567", null,
                new BigDecimal("0.313611"), new BigDecimal("32.581111"), null, null, null,
                250, null, null, "UNKNOWN", null, null, null);
    }

    private static CellInput full(LocalDate verified) {
        return new CellInput("LTE", "001", "01", "4660", "1234567", "Kampala Central",
                new BigDecimal("0.313611"), new BigDecimal("32.581111"), new BigDecimal("30.0"),
                120, 65, 250, 101, 6300, "MACRO", "RF plan", null, verified);
    }

    @Test
    void coordinatesAndRadiusAreWorthSixty() {
        assertThat(CellQuality.score(minimal())).isEqualTo(60);
    }

    @Test
    void aCompleteFreshRecordScoresOneHundred() {
        assertThat(CellQuality.score(full(LocalDate.now().minusDays(10)))).isEqualTo(100);
    }

    @Test
    void aStaleVerificationCostsTenPoints() {
        assertThat(CellQuality.score(full(LocalDate.now().minusDays(400)))).isEqualTo(90);
    }

    @Test
    void aRecordWithNoGeometryScoresOnlyForWhatItHas() {
        CellInput noGeometry = new CellInput("LTE", "001", "01", "4660", "1234567", null,
                null, null, null, null, null, null, null, null, "UNKNOWN", null, null, null);

        assertThat(CellQuality.score(noGeometry)).isZero();
    }
}
