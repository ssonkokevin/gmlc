package com.hamiltel.gmlc.cell;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CellIdentityTest {

    @Test
    void lteAndNrIdentitiesKeepAllFourParts() {
        assertThat(CellIdentity.compose(CellIdentity.Rat.LTE, "001", "01", "4660", "1234567"))
                .isEqualTo("001-01-4660-1234567");
        assertThat(CellIdentity.compose(CellIdentity.Rat.NR, "001", "01", "4660", "123456789012"))
                .isEqualTo("001-01-4660-123456789012");
    }

    @Test
    void aThreeDigitMncIsParsedAsOnePart() {
        CellIdentity.Parts parts = CellIdentity.parse(CellIdentity.Rat.LTE,
                "001-011-4660-1234567");

        assertThat(parts.mcc()).isEqualTo("001");
        assertThat(parts.mnc()).isEqualTo("011");
        assertThat(parts.areaCode()).isEqualTo("4660");
        assertThat(parts.cellCode()).isEqualTo("1234567");
    }

    @Test
    void leadingZerosDoNotCreateASecondCell() {
        assertThat(CellIdentity.compose(CellIdentity.Rat.GSM, "001", "01", "04660", "0001234"))
                .isEqualTo("001-01-4660-1234");
        assertThat(CellIdentity.canonicalNumber("0")).isEqualTo("0");
    }

    @Test
    void anIdentityWithTheWrongNumberOfPartsIsRefused() {
        assertThatThrownBy(() -> CellIdentity.parse(CellIdentity.Rat.LTE, "001-01-4660"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mcc-mnc-area-cell");
    }

    @Test
    void cellCodeCeilingsFollowTheRat() {
        assertThat(CellIdentity.Rat.GSM.maxCellCode()).isEqualTo(65_535);
        assertThat(CellIdentity.Rat.UMTS.maxCellCode()).isEqualTo(268_435_455);
        assertThat(CellIdentity.Rat.LTE.maxCellCode()).isEqualTo(268_435_455);
        assertThat(CellIdentity.Rat.NR.maxCellCode()).isEqualTo(68_719_476_735L);
    }

    @Test
    void ratParsingIsCaseInsensitiveAndRejectsUnknownNames() {
        assertThat(CellIdentity.Rat.of("lte")).isEqualTo(CellIdentity.Rat.LTE);
        assertThatThrownBy(() -> CellIdentity.Rat.of("WIFI")).isInstanceOf(IllegalArgumentException.class);
    }
}
