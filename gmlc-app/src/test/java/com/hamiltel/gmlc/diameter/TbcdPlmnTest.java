package com.hamiltel.gmlc.diameter;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TbcdPlmnTest {

    @Test
    void tbcdPutsTheFirstDigitInTheLowNibble() {
        assertThat(Tbcd.encode("256700000001"))
                .isEqualTo(new byte[] {0x52, 0x76, 0x00, 0x00, 0x00, 0x10});
    }

    @Test
    void anOddDigitCountIsPaddedWithFifteen() {
        assertThat(Tbcd.encode("256")).isEqualTo(new byte[] {0x52, (byte) 0xF6});
        assertThat(Tbcd.decode(new byte[] {0x52, (byte) 0xF6})).isEqualTo("256");
    }

    @Test
    void onlyDecimalDigitsAreAccepted() {
        assertThatThrownBy(() -> Tbcd.encode("256x")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aTwoDigitMncSetsTheFillerNibble() {
        assertThat(PlmnCodec.encode("001", "01")).isEqualTo(new byte[] {0x00, (byte) 0xF1, 0x10});
    }

    @Test
    void aThreeDigitMncUsesAllThreeNibbles() {
        assertThat(PlmnCodec.encode("001", "001")).isEqualTo(new byte[] {0x00, 0x11, 0x00});
    }

    @Test
    void decodingRestoresTheDigitsForBothMncLengths() {
        assertThat(PlmnCodec.decode(new byte[] {0x00, (byte) 0xF1, 0x10}))
                .isEqualTo(new PlmnCodec.Plmn("001", "01"));
        assertThat(PlmnCodec.decode(new byte[] {0x00, 0x11, 0x00}))
                .isEqualTo(new PlmnCodec.Plmn("001", "001"));
    }

    @Test
    void thePlmnLayoutMatchesTheEcgiOctetsOfTs29274() {
        assertThat(PlmnCodec.encode("001", "01")).isEqualTo(new byte[] {0x00, (byte) 0xF1, 0x10});
        assertThat(PlmnCodec.encode("262", "01")).isEqualTo(new byte[] {0x62, (byte) 0xF2, 0x10});
    }

    @Test
    void aNibbleThatIsNotADigitIsRefused() {
        assertThatThrownBy(() -> PlmnCodec.decode(new byte[] {0x0A, 0x1F, 0x10}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theUncertaintyCodeIsTheSmallestRadiusThatCoversTheRequest() {
        assertThat(UncertaintyCode.codeForMetres(0)).isZero();
        assertThat(UncertaintyCode.radiusMetres(UncertaintyCode.codeForMetres(50)))
                .isGreaterThanOrEqualTo(50);
        assertThat(UncertaintyCode.radiusMetres(UncertaintyCode.codeForMetres(50) - 1))
                .isLessThan(50);
        assertThat(UncertaintyCode.radiusMetres(UncertaintyCode.codeForMetres(1200)))
                .isGreaterThanOrEqualTo(1200);
    }
}
