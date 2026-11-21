package com.hamiltel.gmlc.map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class GadTest {

    /** 0.3476 N, 32.5825 E with uncertainty code 45 (a radius of 719 m). */
    private static final byte[] KAMPALA = {
            0x01, 0x00, 0x7e, (byte) 0x8e, 0x17, 0x2b, 0x76, 0x2d};

    @Test
    void aKnownUncertaintyCircleDecodesToItsCoordinatesAndRadius() {
        Gad.Circle circle = Gad.decodeUncertaintyCircle(KAMPALA);

        assertThat(circle.latitude()).isCloseTo(0.3476, within(3e-5));
        assertThat(circle.longitude()).isCloseTo(32.5825, within(3e-5));
        assertThat(circle.uncertaintyMetres()).isEqualTo(719);
    }

    @Test
    void theSameCircleEncodesToTheSameOctets() {
        assertThat(Gad.encodeCircle(0.3476, 32.5825, 45)).isEqualTo(KAMPALA);
    }

    @Test
    void aSouthernAndWesternPointKeepsBothSigns() {
        byte[] encoded = Gad.encodeCircle(-1.2921, -36.8219, 60);

        assertThat(encoded[1] & 0x80).isNotZero();
        Gad.Circle circle = Gad.decodeUncertaintyCircle(encoded);
        assertThat(circle.latitude()).isCloseTo(-1.2921, within(3e-5));
        assertThat(circle.longitude()).isCloseTo(-36.8219, within(3e-5));
        assertThat(circle.uncertaintyMetres()).isEqualTo(3035);
    }

    @Test
    void aPointInsideTheRadiusIsInsideAndAFarPointIsNot() {
        Gad.Circle circle = Gad.decodeUncertaintyCircle(KAMPALA);

        assertThat(circle.inside(0.3477, 32.5826)).isTrue();
        assertThat(circle.inside(0.4476, 32.5825)).isFalse();
    }

    @Test
    void aDifferentShapeIsNamedInTheFailureRatherThanApproximated() {
        byte[] point = {0x00, 0x00, 0x7e, (byte) 0x8e, 0x17, 0x2b, 0x76, 0x00};

        assertThatThrownBy(() -> Gad.decodeUncertaintyCircle(point))
                .isInstanceOfSatisfying(MapException.class, failure -> {
                    assertThat(failure.code()).isEqualTo("GAD_SHAPE");
                    assertThat(failure).hasMessageContaining("uncertainty circle");
                });
    }

    @Test
    void aCircleOfTheWrongLengthOrWithTheSpareBitSetIsRefused() {
        byte[] shortCircle = {0x01, 0x00, 0x7e, (byte) 0x8e, 0x17, 0x2b, 0x76};
        byte[] spareSet = {0x01, 0x00, 0x7e, (byte) 0x8e, 0x17, 0x2b, 0x76, (byte) 0xad};

        assertThatThrownBy(() -> Gad.decodeUncertaintyCircle(shortCircle))
                .isInstanceOfSatisfying(MapException.class,
                        failure -> assertThat(failure.code()).isEqualTo("GAD_LENGTH"));
        assertThatThrownBy(() -> Gad.decodeUncertaintyCircle(spareSet))
                .isInstanceOfSatisfying(MapException.class,
                        failure -> assertThat(failure.code()).isEqualTo("GAD_UNCERTAINTY"));
    }

    @Test
    void anEmptyDescriptionIsRefused() {
        assertThatThrownBy(() -> Gad.decodeUncertaintyCircle(new byte[0]))
                .isInstanceOfSatisfying(MapException.class,
                        failure -> assertThat(failure.code()).isEqualTo("GAD_EMPTY"));
    }

    @Test
    void anUncertaintyCodeBeyondTheTableIsRefused() {
        assertThatThrownBy(() -> Gad.encodeCircle(0.3, 32.5, 128))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
