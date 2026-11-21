package com.hamiltel.gmlc.ss7;

import com.hamiltel.gmlc.map.MapException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SccpTest {

    private static Sccp.UnitData request(byte[] data) {
        return new Sccp.UnitData(Sccp.Address.subsystem(Sccp.SSN_HLR),
                Sccp.Address.subsystem(3, Sccp.SSN_GMLC), data);
    }

    @Test
    void aSubsystemOnlyAddressIsTheIndicatorAndTheSubsystemNumber() {
        assertThat(Sccp.Address.subsystem(Sccp.SSN_HLR).encode())
                .isEqualTo(new byte[] {0x42, 0x06});
    }

    @Test
    void anAddressThatNamesItsPointCodePutsItBeforeTheSubsystemNumber() {
        // 0x43: point code and subsystem present, route on them; the point code
        // is 0x1234, low octet first, and the second octet keeps bits 7-8 clear.
        assertThat(Sccp.Address.subsystem(0x1234, Sccp.SSN_MSC).encode())
                .isEqualTo(new byte[] {0x43, 0x34, 0x12, 0x08});
    }

    @Test
    void anEvenGlobalTitleCarriesTranslationTypeNumberingPlanAndInternationalDigits() {
        // 0x12: global title indicator 4 and a subsystem number, route on the
        // title. Then translation type 0, ISDN/E.164 with even-length BCD
        // (0x11), nature of address 4, and the digits low nibble first.
        byte[] encoded = Sccp.Address.globalTitle(Sccp.SSN_HLR, "256700000001").encode();

        assertThat(encoded).isEqualTo(new byte[] {
                0x12, 0x06, 0x00, 0x11, 0x04,
                0x52, 0x76, 0x00, 0x00, 0x00, 0x10});
    }

    @Test
    void anOddGlobalTitlePadsItsLastOctetWithZeroNotF() {
        byte[] encoded = Sccp.Address.globalTitle(Sccp.SSN_HLR, "25670000001").encode();

        assertThat(encoded).isEqualTo(new byte[] {
                0x12, 0x06, 0x00, 0x10, 0x04,
                0x52, 0x76, 0x00, 0x00, 0x00, 0x01});
    }

    @Test
    void everyAddressFormParsesBackToWhatWasEncoded() {
        Sccp.Address[] addresses = {
                Sccp.Address.subsystem(Sccp.SSN_SGSN),
                Sccp.Address.subsystem(0x2FFF, Sccp.SSN_MSC),
                Sccp.Address.globalTitle(Sccp.SSN_HLR, "256700000001"),
                Sccp.Address.globalTitle(Sccp.SSN_HLR, "25670000001"),
                // Q.713 section 3.5: the calling party may be just an empty indicator.
                new Sccp.Address(0, null, null)
        };

        for (Sccp.Address address : addresses) {
            assertThat(Sccp.Address.parse(address.encode())).isEqualTo(address);
        }
    }

    @Test
    void aUnitdataMessageIsTheTypeClassThreePointersAndTheTwoAddresses() {
        byte[] encoded = request(new byte[] {0x62, 0x01, 0x02}).encode();

        // Called: subsystem 6 only. Calling: point code 3 and subsystem 145
        // (0x91), four octets, so the data pointer counts five header, two
        // called and four calling octets.
        assertThat(encoded).isEqualTo(new byte[] {
                0x09, 0x00, 0x03, 0x04, 0x07,
                0x42, 0x06,
                0x43, 0x03, 0x00, (byte) 0x91,
                0x62, 0x01, 0x02});
    }

    @Test
    void aUnitdataMessageParsesBackToItsPartiesAndPayload() {
        Sccp.UnitData sent = new Sccp.UnitData(Sccp.Address.globalTitle(Sccp.SSN_HLR, "256700000001"),
                Sccp.Address.subsystem(0x1234, Sccp.SSN_GMLC),
                new byte[] {0x6b, 0x1a, 0x28});

        Sccp.UnitData parsed = Sccp.UnitData.parse(sent.encode());

        assertThat(parsed.calledParty()).isEqualTo(sent.calledParty());
        assertThat(parsed.callingParty()).isEqualTo(sent.callingParty());
        assertThat(parsed.data()).isEqualTo(sent.data());
    }

    @Test
    void aUnitdataMessageOfExactly255OctetsIsAllowedAndOneMoreIsNot() {
        // 5 header + 2 called + 4 calling leaves 244 octets for the payload.
        Sccp.UnitData fits = new Sccp.UnitData(Sccp.Address.subsystem(Sccp.SSN_HLR),
                Sccp.Address.subsystem(3, Sccp.SSN_GMLC), new byte[244]);
        assertThat(fits.encode()).hasSize(255);

        Sccp.UnitData tooLarge = new Sccp.UnitData(Sccp.Address.subsystem(Sccp.SSN_HLR),
                Sccp.Address.subsystem(3, Sccp.SSN_GMLC), new byte[245]);
        assertThatThrownBy(tooLarge::encode)
                .isInstanceOf(MapException.class)
                .satisfies(error -> assertThat(((MapException) error).code()).isEqualTo("SCCP_TOO_LONG"));
    }

    @Test
    void aMessageThatIsNotUnitdataIsRefused() {
        byte[] frame = request(new byte[] {0x62}).encode();
        frame[0] = 0x0A;

        assertThatThrownBy(() -> Sccp.UnitData.parse(frame))
                .isInstanceOf(MapException.class)
                .satisfies(error -> assertThat(((MapException) error).code()).isEqualTo("SCCP_UNSUPPORTED"));
    }

    @Test
    void aConnectionOrientedClassIsRefusedRatherThanMisread() {
        byte[] frame = request(new byte[] {0x62}).encode();
        frame[1] = 0x02;

        assertThatThrownBy(() -> Sccp.UnitData.parse(frame))
                .isInstanceOf(MapException.class)
                .satisfies(error -> assertThat(((MapException) error).code()).isEqualTo("SCCP_PROTOCOL_CLASS"));
    }

    @Test
    void aPointerOutsideTheMessageIsRefused() {
        byte[] frame = request(new byte[] {0x62}).encode();
        frame[4] = (byte) 0xF0;

        assertThatThrownBy(() -> Sccp.UnitData.parse(frame))
                .isInstanceOf(MapException.class)
                .satisfies(error -> assertThat(((MapException) error).code()).isEqualTo("SCCP_POINTER"));
    }

    @Test
    void aGlobalTitleFormatThisGatewayDoesNotSendIsRefused() {
        // Indicator 0x0A is global title type 2: translation type only.
        byte[] address = {0x0A, 0x06};

        assertThatThrownBy(() -> Sccp.Address.parse(address))
                .isInstanceOf(MapException.class)
                .satisfies(error -> assertThat(((MapException) error).code()).isEqualTo("SCCP_GT_UNSUPPORTED"));
    }

    @Test
    void aNumberingPlanThatIsNotE164IsRefused() {
        byte[] address = {0x12, 0x06, 0x00, 0x21, 0x04, 0x52};

        assertThatThrownBy(() -> Sccp.Address.parse(address))
                .isInstanceOf(MapException.class)
                .satisfies(error -> assertThat(((MapException) error).code()).isEqualTo("SCCP_GT_UNSUPPORTED"));
    }

    @Test
    void aNatureOfAddressThatIsNotInternationalIsRefused() {
        byte[] address = {0x12, 0x06, 0x00, 0x11, 0x03, 0x52};

        assertThatThrownBy(() -> Sccp.Address.parse(address))
                .isInstanceOf(MapException.class)
                .satisfies(error -> assertThat(((MapException) error).code()).isEqualTo("SCCP_GT_UNSUPPORTED"));
    }

    @Test
    void aFillerNibbleThatIsNotZeroAfterAnOddDigitCountIsRefused() {
        byte[] address = {0x12, 0x06, 0x00, 0x10, 0x04, 0x21};

        assertThatThrownBy(() -> Sccp.Address.parse(address))
                .isInstanceOf(MapException.class)
                .satisfies(error -> assertThat(((MapException) error).code()).isEqualTo("SCCP_GT_DIGITS"));
    }

    @Test
    void aDigitCodeAboveNineIsRefused() {
        byte[] address = {0x12, 0x06, 0x00, 0x11, 0x04, 0x5A};

        assertThatThrownBy(() -> Sccp.Address.parse(address))
                .isInstanceOf(MapException.class)
                .satisfies(error -> assertThat(((MapException) error).code()).isEqualTo("SCCP_GT_DIGITS"));
    }

    @Test
    void aPointCodeWithItsSpareBitsSetIsRefused() {
        byte[] address = {0x43, 0x34, (byte) 0xC2, 0x08};

        assertThatThrownBy(() -> Sccp.Address.parse(address))
                .isInstanceOf(MapException.class)
                .satisfies(error -> assertThat(((MapException) error).code()).isEqualTo("SCCP_ADDRESS"));
    }

    @Test
    void aGlobalTitleLongerThanAnE164NumberIsRefusedAtConstruction() {
        assertThatThrownBy(() -> Sccp.Address.globalTitle(Sccp.SSN_HLR, "2567000000012345"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Sccp.Address.globalTitle(Sccp.SSN_HLR, "2567*"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
