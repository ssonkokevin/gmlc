package com.hamiltel.gmlc.diameter;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AvpTest {

    @Test
    void aShortBaseAvpIsPaddedToAFourOctetBoundary() {
        byte[] encoded = Avp.baseUtf8(DiameterDictionary.ORIGIN_HOST, "a").encode();

        // code 264, flags M, length 9, then one octet of value and three of padding.
        assertThat(encoded).hasSize(12);
        assertThat(java.util.Arrays.copyOf(encoded, 8))
                .isEqualTo(new byte[] {0, 0, 1, 8, 0x40, 0, 0, 9});
        assertThat(encoded[8]).isEqualTo((byte) 'a');
        assertThat(encoded[9]).isEqualTo((byte) 0);
        assertThat(encoded[10]).isEqualTo((byte) 0);
        assertThat(encoded[11]).isEqualTo((byte) 0);
    }

    @Test
    void aVendorSpecificAvpCarriesTheVendorIdAndTheVFlag() {
        byte[] encoded = Avp.vendor(DiameterDictionary.MSISDN, new byte[] {0x52, 0x76}).encode();

        assertThat(encoded).hasSize(16);
        assertThat(encoded[4]).isEqualTo((byte) 0xC0);
        // length 14: 12 octets of header plus the two-octet value.
        assertThat(encoded[5]).isEqualTo((byte) 0);
        assertThat(encoded[6]).isEqualTo((byte) 0);
        assertThat(encoded[7]).isEqualTo((byte) 14);
        assertThat(encoded[8]).isEqualTo((byte) 0);
        assertThat(encoded[9]).isEqualTo((byte) 0);
        assertThat(encoded[10]).isEqualTo((byte) 0x28);
        assertThat(encoded[11]).isEqualTo((byte) 0xAF);
        assertThat(encoded[12]).isEqualTo((byte) 0x52);
        assertThat(encoded[13]).isEqualTo((byte) 0x76);
    }

    @Test
    void aRunOfAvpsParsesBackToTheSameCodesAndValues() {
        byte[] data = Avp.encodeAll(List.of(
                Avp.baseUtf8(DiameterDictionary.ORIGIN_HOST, "gmlc.lab.local"),
                Avp.baseU32(DiameterDictionary.AUTH_SESSION_STATE, 1),
                Avp.vendorGrouped(DiameterDictionary.LCS_QOS,
                        Avp.vendorU32(DiameterDictionary.HORIZONTAL_ACCURACY, 12))));

        List<Avp> parsed = Avp.parseAll(data);

        assertThat(parsed).extracting(Avp::code).containsExactly(
                DiameterDictionary.ORIGIN_HOST,
                DiameterDictionary.AUTH_SESSION_STATE,
                DiameterDictionary.LCS_QOS);
        assertThat(parsed.get(0).asText()).isEqualTo("gmlc.lab.local");
        assertThat(parsed.get(1).asUnsigned32()).isEqualTo(1);
        assertThat(parsed.get(2).vendorId()).isEqualTo(Avp.THREE_GPP);
        assertThat(parsed.get(2).children()).singleElement()
                .satisfies(child -> {
                    assertThat(child.code()).isEqualTo(DiameterDictionary.HORIZONTAL_ACCURACY);
                    assertThat(child.asInt()).isEqualTo(12);
                });
    }

    @Test
    void aTruncatedAvpIsRefusedRatherThanGuessed() {
        byte[] full = Avp.baseUtf8(DiameterDictionary.ORIGIN_HOST, "gmlc.lab.local").encode();
        byte[] truncated = java.util.Arrays.copyOf(full, 10);

        assertThatThrownBy(() -> Avp.parseAll(truncated))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("runs past the message");
    }

    @Test
    void aDeclaredLengthShorterThanItsHeaderIsRefused() {
        byte[] hostile = new byte[12];
        hostile[3] = 1;              // code 1
        hostile[4] = 0x40;
        hostile[7] = 4;              // length 4, less than an eight-octet header

        assertThatThrownBy(() -> Avp.parseAll(hostile))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("declared length");
    }

    @Test
    void anAddressCarriesItsTypeOctets() {
        Avp avp = Avp.address(DiameterDictionary.HOST_IP_ADDRESS, new byte[] {10, 0, 0, 7});

        assertThat(avp.copyOfValue()).isEqualTo(new byte[] {0, 1, 10, 0, 0, 7});
    }

    @Test
    void aPaddedTextValueIsReadWithoutItsTrailingNuls() {
        Avp avp = new Avp(DiameterDictionary.ORIGIN_HOST, false, 0,
                new byte[] {'m', 'm', 'e', '.', 'l', 'a', 'b', 0, 0});

        assertThat(avp.asText()).isEqualTo("mme.lab");
    }

    @Test
    void aFixedLengthValueDropsUpToThreeTrailingPaddingOctets() {
        byte[] padded = {0x00, (byte) 0xF1, 0x10, 0x00, 0x0A, 0x1B, 0x2C, 0x00};

        assertThat(Avp.vendor(DiameterDictionary.ECGI, padded).fixedValue(7))
                .isEqualTo(new byte[] {0x00, (byte) 0xF1, 0x10, 0x00, 0x0A, 0x1B, 0x2C});
    }

    @Test
    void aFixedLengthValueOfTheWrongLengthIsRefused() {
        assertThatThrownBy(() -> Avp.vendor(DiameterDictionary.ECGI, new byte[6]).fixedValue(7))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("6 octets");
        assertThatThrownBy(() -> Avp.vendor(DiameterDictionary.ECGI, new byte[11]).fixedValue(7))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> Avp.vendor(DiameterDictionary.ECGI,
                new byte[] {0x00, (byte) 0xF1, 0x10, 0x00, 0x0A, 0x1B, 0x2C, 0x01}).fixedValue(7))
                .isInstanceOf(IllegalStateException.class);
    }
}
