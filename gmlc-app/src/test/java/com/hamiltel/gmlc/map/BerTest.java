package com.hamiltel.gmlc.map;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BerTest {

    @Test
    void aMapApplicationContextEncodesToItsKnownOctets() {
        // 0.4.0.0.1.0.37.3 is locationSvcGatewayContext-v3 in TS 29.002.
        assertThat(Ber.oid(0, 4, 0, 0, 1, 0, 37, 3))
                .isEqualTo(new byte[] {0x06, 0x07, 0x04, 0x00, 0x00, 0x01, 0x00, 0x25, 0x03});
    }

    @Test
    void theStructuredDialogueObjectIdentifierEncodesToItsKnownOctets() {
        // 0.0.17.773.1.1.1 is dialogue-as in Q.773.
        assertThat(Ber.oid(0, 0, 17, 773, 1, 1, 1))
                .isEqualTo(new byte[] {0x06, 0x07, 0x00, 0x11, (byte) 0x86, 0x05, 0x01, 0x01, 0x01});
    }

    @Test
    void anObjectIdentifierUnderArcTwoWithALargeSecondArcRoundTrips() {
        byte[] encoded = Ber.oid(2, 999, 3);

        assertThat(encoded).isEqualTo(new byte[] {0x06, 0x03, (byte) 0x88, 0x37, 0x03});
        assertThat(Ber.parse(encoded).oidArcs()).containsExactly(2, 999, 3);
    }

    @Test
    void anObjectIdentifierWithATruncatedFinalArcIsRefused() {
        byte[] encoded = {0x06, 0x02, 0x04, (byte) 0x86};

        assertThatThrownBy(() -> Ber.parse(encoded).oidArcs())
                .isInstanceOf(BerException.class)
                .hasMessageContaining("truncated");
    }

    @Test
    void integersUseTheShortestSignedEncoding() {
        assertThat(Ber.integer(0)).isEqualTo(new byte[] {0x02, 0x01, 0x00});
        assertThat(Ber.integer(85)).isEqualTo(new byte[] {0x02, 0x01, 0x55});
        assertThat(Ber.integer(127)).isEqualTo(new byte[] {0x02, 0x01, 0x7f});
        assertThat(Ber.integer(128)).isEqualTo(new byte[] {0x02, 0x02, 0x00, (byte) 0x80});
        assertThat(Ber.integer(255)).isEqualTo(new byte[] {0x02, 0x02, 0x00, (byte) 0xff});
        assertThat(Ber.integer(-1)).isEqualTo(new byte[] {0x02, 0x01, (byte) 0xff});
        assertThat(Ber.integer(256)).isEqualTo(new byte[] {0x02, 0x02, 0x01, 0x00});
    }

    @Test
    void aNegativeIntegerParsesBackWithItsSign() {
        assertThat(Ber.parse(new byte[] {0x02, 0x01, (byte) 0xff}).intValue()).isEqualTo(-1);
        assertThat(Ber.parse(new byte[] {0x02, 0x01, (byte) 0x80}).intValue()).isEqualTo(-128);
    }

    @Test
    void aLengthOfOneHundredAndTwentyEightUsesTheLongForm() {
        byte[] encoded = Ber.octets(new byte[128]);

        assertThat(Arrays.copyOf(encoded, 3)).isEqualTo(new byte[] {0x04, (byte) 0x81, (byte) 0x80});
        assertThat(encoded).hasSize(131);
        assertThat(Ber.parse(encoded).value()).hasSize(128);
    }

    @Test
    void aLengthOfTwoHundredAndFiftySixUsesTheLongForm() {
        byte[] encoded = Ber.octets(new byte[256]);

        assertThat(Arrays.copyOf(encoded, 4))
                .isEqualTo(new byte[] {0x04, (byte) 0x82, 0x01, 0x00});
        assertThat(encoded).hasSize(260);
    }

    @Test
    void anIndefiniteLengthIsRefused() {
        assertThatThrownBy(() -> Ber.parse(new byte[] {0x30, (byte) 0x80}))
                .isInstanceOf(BerException.class)
                .hasMessageContaining("indefinite");
    }

    @Test
    void aHighTagNumberIsRefused() {
        assertThatThrownBy(() -> Ber.parse(new byte[] {0x1f, 0x01, 0x00}))
                .isInstanceOf(BerException.class)
                .hasMessageContaining("high-tag-number");
    }

    @Test
    void aLengthThatWouldFitInTheShortFormIsRefusedWhenLongFormIsUsed() {
        assertThatThrownBy(() -> Ber.parse(new byte[] {0x04, (byte) 0x81, 0x05, 1, 2, 3, 4, 5}))
                .isInstanceOf(BerException.class)
                .hasMessageContaining("short form");
    }

    @Test
    void aTruncatedLengthAndAnOverrunningValueAreRefused() {
        assertThatThrownBy(() -> Ber.parse(new byte[] {0x02}))
                .isInstanceOf(BerException.class)
                .hasMessageContaining("truncated length");

        assertThatThrownBy(() -> Ber.parse(new byte[] {0x02, 0x05, 0x01}))
                .isInstanceOf(BerException.class)
                .hasMessageContaining("runs past");
    }

    @Test
    void parseDemandsExactlyOneElement() {
        assertThatThrownBy(() -> Ber.parse(new byte[] {0x02, 0x01, 0x00, 0x02, 0x01, 0x01}))
                .isInstanceOf(BerException.class)
                .hasMessageContaining("exactly one element");
    }

    @Test
    void aSequenceIsReadAsItsChildren() {
        byte[] encoded = Ber.sequence(Ber.integer(1), Ber.octets("ab"));

        assertThat(Ber.parse(encoded).children())
                .extracting(Ber.Tlv::tag)
                .containsExactly(Ber.INTEGER, Ber.OCTET_STRING);
    }

    @Test
    void aPrimitiveElementHasNoChildren() {
        Ber.Tlv integer = Ber.parse(Ber.integer(1));

        assertThatThrownBy(integer::children)
                .isInstanceOf(BerException.class)
                .hasMessageContaining("primitive");
    }
}
