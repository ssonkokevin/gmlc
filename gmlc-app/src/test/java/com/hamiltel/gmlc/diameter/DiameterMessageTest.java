package com.hamiltel.gmlc.diameter;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiameterMessageTest {

    @Test
    void aRequestRoundTripsThroughItsWireForm() {
        DiameterMessage request = DiameterMessage.request(
                DiameterDictionary.LCS_ROUTING_INFO, DiameterDictionary.SLH_APPLICATION_ID,
                List.of(Avp.baseUtf8(DiameterDictionary.ORIGIN_HOST, "gmlc.lab.local")))
                .withIds(0x11223344, 0x55667788);

        byte[] wire = request.encode();
        DiameterMessage parsed = DiameterMessage.parse(wire);

        assertThat(wire[0]).isEqualTo((byte) 1);
        assertThat(wire[4]).isEqualTo((byte) 0xC0);
        assertThat(parsed.request()).isTrue();
        assertThat(parsed.proxiable()).isTrue();
        assertThat(parsed.commandCode()).isEqualTo(DiameterDictionary.LCS_ROUTING_INFO);
        assertThat(parsed.applicationId()).isEqualTo(DiameterDictionary.SLH_APPLICATION_ID);
        assertThat(parsed.hopByHopId()).isEqualTo(0x11223344);
        assertThat(parsed.endToEndId()).isEqualTo(0x55667788);
        assertThat(parsed.text(DiameterDictionary.ORIGIN_HOST)).isEqualTo("gmlc.lab.local");
    }

    @Test
    void anAnswerClearsTheRequestBitAndKeepsTheCorrelationIds() {
        DiameterMessage request = DiameterMessage.request(
                DiameterDictionary.PROVIDE_LOCATION, DiameterDictionary.SLG_APPLICATION_ID, List.of());
        DiameterMessage answer = request.answer(7, 9,
                List.of(Avp.baseU32(DiameterDictionary.RESULT_CODE, 2001)));

        DiameterMessage parsed = DiameterMessage.parse(answer.encode());

        assertThat(parsed.request()).isFalse();
        assertThat(parsed.hopByHopId()).isEqualTo(7);
        assertThat(parsed.endToEndId()).isEqualTo(9);
        assertThat(DiameterResult.codeOf(parsed)).isEqualTo(DiameterDictionary.DIAMETER_SUCCESS);
    }

    @Test
    void aWrongVersionIsRefused() {
        byte[] wire = DiameterMessage.request(1, 0, List.of()).encode();
        wire[0] = 2;

        assertThatThrownBy(() -> DiameterMessage.parse(wire))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version");
    }

    @Test
    void aLengthBeyondTheFrameIsRefused() {
        byte[] wire = DiameterMessage.request(1, 0, List.of()).encode();
        wire[3] = (byte) (wire.length + 4);

        assertThatThrownBy(() -> DiameterMessage.parse(wire))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("declared length");
    }

    @Test
    void aStreamFrameCarriesAFourOctetLengthPrefix() {
        byte[] payload = DiameterMessage.request(1, 0, List.of()).encode();
        byte[] framed = DiameterMessage.frame(payload);

        assertThat(framed).hasSize(payload.length + 4);
        assertThat(framed[0]).isZero();
        assertThat(framed[1]).isZero();
        assertThat(framed[2]).isEqualTo((byte) 0);
        assertThat(framed[3]).isEqualTo((byte) payload.length);
    }
}
