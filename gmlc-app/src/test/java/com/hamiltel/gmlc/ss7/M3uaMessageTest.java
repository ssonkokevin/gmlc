package com.hamiltel.gmlc.ss7;

import com.hamiltel.gmlc.map.MapException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class M3uaMessageTest {

    @Test
    void aspUpIsClassThreeTypeOneWithTheAspIdentifier() {
        assertThat(M3uaMessage.aspUp(1, "").encode()).isEqualTo(new byte[] {
                0x01, 0x00, 0x03, 0x01, 0x00, 0x00, 0x00, 0x10,
                0x00, 0x11, 0x00, 0x08, 0x00, 0x00, 0x00, 0x01});
    }

    @Test
    void anInfoStringIsPaddedButItsLengthDoesNotCountThePadding() {
        M3uaMessage ack = M3uaMessage.of(M3uaMessage.CLASS_ASPSM, M3uaMessage.TYPE_ASPUP_ACK,
                M3uaMessage.Param.text(M3uaMessage.TAG_INFO_STRING, "abc"));

        assertThat(ack.encode()).isEqualTo(new byte[] {
                0x01, 0x00, 0x03, 0x04, 0x00, 0x00, 0x00, 0x10,
                0x00, 0x04, 0x00, 0x07, 0x61, 0x62, 0x63, 0x00});
    }

    @Test
    void aspActiveCarriesTheOverrideTrafficModeAndOptionallyARoutingContext() {
        assertThat(M3uaMessage.aspActive(null).encode()).isEqualTo(new byte[] {
                0x01, 0x00, 0x04, 0x01, 0x00, 0x00, 0x00, 0x10,
                0x00, 0x0B, 0x00, 0x08, 0x00, 0x00, 0x00, 0x01});

        assertThat(M3uaMessage.aspActive(7).encode()).isEqualTo(new byte[] {
                0x01, 0x00, 0x04, 0x01, 0x00, 0x00, 0x00, 0x18,
                0x00, 0x0B, 0x00, 0x08, 0x00, 0x00, 0x00, 0x01,
                0x00, 0x06, 0x00, 0x08, 0x00, 0x00, 0x00, 0x07});
    }

    @Test
    void dataCarriesThePointCodesUserPartAndSignallingLinkSelection() {
        // Protocol data: OPC 1, DPC 2, SI 3 (SCCP), NI 2 (national), priority 0,
        // SLS 0, then one octet of SCCP. Its 13 octets pad to 16.
        assertThat(M3uaMessage.data(1, 2, M3uaMessage.SI_SCCP, 2, 0, null, new byte[] {0x09}).encode())
                .isEqualTo(new byte[] {
                        0x01, 0x00, 0x01, 0x01, 0x00, 0x00, 0x00, 0x1C,
                        0x02, 0x10, 0x00, 0x11,
                        0x00, 0x00, 0x00, 0x01,
                        0x00, 0x00, 0x00, 0x02,
                        0x03, 0x02, 0x00, 0x00,
                        0x09, 0x00, 0x00, 0x00});
    }

    @Test
    void dataWithARoutingContextPutsTheContextBeforeTheProtocolData() {
        byte[] encoded = M3uaMessage.data(1, 2, M3uaMessage.SI_SCCP, 2, 0, 7, new byte[] {0x09}).encode();

        assertThat(encoded).hasSize(36);
        assertThat(encoded[4]).isEqualTo((byte) 0);
        assertThat(encoded[5]).isEqualTo((byte) 0);
        assertThat(encoded[6]).isEqualTo((byte) 0);
        assertThat(encoded[7]).isEqualTo((byte) 36);
        assertThat(encoded[8]).isEqualTo((byte) 0x00);
        assertThat(encoded[9]).isEqualTo((byte) 0x06);
        assertThat(encoded[16]).isEqualTo((byte) 0x02);
        assertThat(encoded[17]).isEqualTo((byte) 0x10);
    }

    @Test
    void aDataMessageParsesBackToTheSameProtocolData() {
        byte[] sccp = {0x09, 0x00, 0x03, 0x04, 0x05, 0x42, 0x06, 0x42, (byte) 0x91, 0x62, 0x00};

        M3uaMessage message = M3uaMessage.parse(
                M3uaMessage.data(0x1234, 0x5678, M3uaMessage.SI_SCCP, 2, 9, 42, sccp).encode());

        assertThat(message.messageClass()).isEqualTo(M3uaMessage.CLASS_TRANSFER);
        assertThat(message.type()).isEqualTo(M3uaMessage.TYPE_DATA);
        assertThat(message.routingContexts()).containsExactly(42);
        M3uaMessage.ProtocolData protocolData = message.protocolData().orElseThrow();
        assertThat(protocolData.originatingPointCode()).isEqualTo(0x1234);
        assertThat(protocolData.destinationPointCode()).isEqualTo(0x5678);
        assertThat(protocolData.serviceIndicator()).isEqualTo(M3uaMessage.SI_SCCP);
        assertThat(protocolData.networkIndicator()).isEqualTo(2);
        assertThat(protocolData.signallingLinkSelection()).isEqualTo(9);
        assertThat(protocolData.userData()).isEqualTo(sccp);
    }

    @Test
    void severalRoutingContextsAreReadAsA32BitRun() {
        M3uaMessage active = M3uaMessage.of(M3uaMessage.CLASS_ASPTM, M3uaMessage.TYPE_ASPAC_ACK,
                new M3uaMessage.Param(M3uaMessage.TAG_ROUTING_CONTEXT,
                        new byte[] {0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x02}));

        assertThat(M3uaMessage.parse(active.encode()).routingContexts()).containsExactly(1, 2);
    }

    @Test
    void anErrorCodeIsA32BitValue() {
        M3uaMessage error = M3uaMessage.of(M3uaMessage.CLASS_ERROR, M3uaMessage.TYPE_ERROR,
                M3uaMessage.Param.u32(M3uaMessage.TAG_ERROR_CODE, 0x19));

        assertThat(M3uaMessage.parse(error.encode()).errorCode()).isEqualTo(0x19);
    }

    @Test
    void anUnknownVersionIsRefused() {
        byte[] frame = M3uaMessage.aspUpAck().encode();
        frame[0] = 0x02;

        assertThatThrownBy(() -> M3uaMessage.parse(frame))
                .isInstanceOf(MapException.class)
                .satisfies(error -> assertThat(((MapException) error).code()).isEqualTo("M3UA_VERSION"));
    }

    @Test
    void aDeclaredLengthThatDoesNotMatchTheFrameIsRefused() {
        byte[] frame = M3uaMessage.aspUpAck().encode();
        frame[7] = (byte) 0x07;

        assertThatThrownBy(() -> M3uaMessage.parse(frame))
                .isInstanceOf(MapException.class)
                .satisfies(error -> assertThat(((MapException) error).code()).isEqualTo("M3UA_FRAME"));
    }

    @Test
    void aParameterThatRunsPastTheFrameIsRefused() {
        byte[] frame = {
                0x01, 0x00, 0x03, 0x01, 0x00, 0x00, 0x00, 0x0C,
                0x00, 0x04, 0x00, 0x08};

        assertThatThrownBy(() -> M3uaMessage.parse(frame))
                .isInstanceOf(MapException.class)
                .satisfies(error -> assertThat(((MapException) error).code()).isEqualTo("M3UA_PARAM"));
    }

    @Test
    void aParameterShorterThanItsOwnHeaderIsRefused() {
        byte[] frame = {
                0x01, 0x00, 0x03, 0x01, 0x00, 0x00, 0x00, 0x0C,
                0x00, 0x04, 0x00, 0x03};

        assertThatThrownBy(() -> M3uaMessage.parse(frame))
                .isInstanceOf(MapException.class)
                .satisfies(error -> assertThat(((MapException) error).code()).isEqualTo("M3UA_PARAM"));
    }
}
