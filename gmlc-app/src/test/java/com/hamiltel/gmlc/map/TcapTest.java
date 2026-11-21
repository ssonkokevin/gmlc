package com.hamiltel.gmlc.map;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TcapTest {

    /** locationSvcGatewayContext-v3, the MAP application context for SLR/SLC. */
    private static final byte[] MAP_AC = Ber.oid(0, 4, 0, 0, 1, 0, 37, 3);

    @Test
    void aBeginWithAnAarqAndOneInvokeMatchesTheKnownWireFormat() {
        byte[] frame = Tcap.begin(1, MAP_AC, new Tcap.Invoke(1, null, 85, null));

        assertThat(frame).isEqualTo(new byte[] {
                0x62, 0x29,
                0x48, 0x01, 0x01,
                0x6b, 0x1a,
                0x28, 0x18,
                0x06, 0x07, 0x00, 0x11, (byte) 0x86, 0x05, 0x01, 0x01, 0x01,
                (byte) 0xa0, 0x0d,
                0x60, 0x0b,
                (byte) 0xa1, 0x09,
                0x06, 0x07, 0x04, 0x00, 0x00, 0x01, 0x00, 0x25, 0x03,
                0x6c, 0x08,
                0x61, 0x06,
                0x02, 0x01, 0x01,
                0x02, 0x01, 0x55});
    }

    @Test
    void aBeginRoundTripsItsTransactionIdApplicationContextAndInvoke() {
        byte[] frame = Tcap.begin(0x010203, MAP_AC, new Tcap.Invoke(7, null, 85, Ber.octets("arg")));

        Tcap.Message message = Tcap.parse(frame);

        assertThat(message.type()).isEqualTo(Tcap.BEGIN);
        assertThat(message.transactionIdValue()).isEqualTo(0x010203);
        assertThat(message.applicationContext()).containsExactly(0, 4, 0, 0, 1, 0, 37, 3);
        assertThat(message.dialogueAccepted()).isTrue();
        assertThat(message.singleComponent())
                .isInstanceOfSatisfying(Tcap.Invoke.class, invoke -> {
                    assertThat(invoke.invokeId()).isEqualTo(7);
                    assertThat(invoke.linkedId()).isNull();
                    assertThat(invoke.opCode()).isEqualTo(85);
                    assertThat(invoke.argument()).isEqualTo(Ber.octets("arg"));
                });
    }

    @Test
    void anInvokeWithALinkedIdKeepsIt() {
        byte[] encoded = new Tcap.Invoke(3, 2, 85, null).encoded();

        Tcap.Invoke invoke = (Tcap.Invoke) Tcap.parse(
                Ber.tlv(Tcap.BEGIN, concat(
                        Ber.tlv(Tcap.ORIG_TRANSACTION, new byte[] {1}),
                        Ber.tlv(Tcap.COMPONENT_PORTION, encoded))))
                .singleComponent();

        assertThat(invoke.invokeId()).isEqualTo(3);
        assertThat(invoke.linkedId()).isEqualTo(2);
    }

    @Test
    void aReturnResultMatchesTheKnownWireFormat() {
        assertThat(Tcap.returnResult(1, 85, Ber.octets("x"))).isEqualTo(new byte[] {
                0x62, 0x0b,
                0x02, 0x01, 0x01,
                0x30, 0x06,
                0x02, 0x01, 0x55,
                0x04, 0x01, 0x78});
    }

    @Test
    void aReturnResultDecodesItsOperationAndResult() {
        Tcap.ReturnResult result = (Tcap.ReturnResult) Tcap.parse(
                Ber.tlv(Tcap.END, concat(
                        Ber.tlv(Tcap.DEST_TRANSACTION, new byte[] {1}),
                        Ber.tlv(Tcap.COMPONENT_PORTION, Tcap.returnResult(1, 85, Ber.octets("x"))))))
                .singleComponent();

        assertThat(result.invokeId()).isEqualTo(1);
        assertThat(result.opCode()).isEqualTo(85);
        assertThat(result.result()).isEqualTo(Ber.octets("x"));
    }

    @Test
    void aReturnErrorDecodesItsErrorCode() {
        byte[] frame = Ber.tlv(Tcap.END, concat(
                Ber.tlv(Tcap.DEST_TRANSACTION, new byte[] {1}),
                Ber.tlv(Tcap.COMPONENT_PORTION, Tcap.returnError(4, 3, null))));

        Tcap.ReturnError error = (Tcap.ReturnError) Tcap.parse(frame).singleComponent();

        assertThat(error.invokeId()).isEqualTo(4);
        assertThat(error.errorCode()).isEqualTo(3);
        assertThat(error.parameter()).isNull();
    }

    @Test
    void anAcceptingAareRoundTripsAsAnAcceptedDialogue() {
        byte[] frame = Tcap.message(Tcap.END, 5, 1, MAP_AC,
                new Tcap.ReturnResult(1, 85, null), true);

        Tcap.Message message = Tcap.parse(frame);

        assertThat(message.type()).isEqualTo(Tcap.END);
        assertThat(message.dialogueResult()).isEqualTo(Tcap.ACCEPTED);
        assertThat(message.dialogueAccepted()).isTrue();
        assertThat(message.applicationContext()).containsExactly(0, 4, 0, 0, 1, 0, 37, 3);
    }

    @Test
    void aRejectingAareIsNotAccepted() {
        byte[] rejected = Ber.tlv(Tcap.END, concat(
                Ber.tlv(Tcap.DEST_TRANSACTION, new byte[] {1}),
                Ber.tlv(Tcap.DIALOGUE_PORTION, Ber.tlv(Ber.EXTERNAL, concat(
                        Tcap.DIALOGUE_AS,
                        Ber.tlv(Ber.contextConstructed(0), Ber.tlv(Tcap.AARE, concat(
                                Ber.tlv(Ber.contextConstructed(1), MAP_AC),
                                Ber.tlv(Ber.contextConstructed(2), Ber.integer(1)),
                                Ber.tlv(Ber.contextConstructed(3),
                                        Ber.tlv(Ber.contextConstructed(1), Ber.integer(0)))))))))));

        Tcap.Message message = Tcap.parse(rejected);

        assertThat(message.dialogueResult()).isEqualTo(1);
        assertThat(message.dialogueAccepted()).isFalse();
    }

    @Test
    void anAbortIsAFailureWithItsCause() {
        byte[] abort = Ber.tlv(Tcap.ABORT, concat(
                Ber.tlv(Tcap.DEST_TRANSACTION, new byte[] {1}),
                Ber.tlv(0x4a, new byte[] {4})));

        assertThatThrownBy(() -> Tcap.parse(abort))
                .isInstanceOfSatisfying(MapException.class, failure -> {
                    assertThat(failure.code()).isEqualTo("TC_ABORT");
                    assertThat(failure).hasMessageContaining("cause 4");
                });
    }

    @Test
    void aGlobalOperationCodeIsRefusedRatherThanGuessed() {
        byte[] frame = Ber.tlv(Tcap.BEGIN, concat(
                Ber.tlv(Tcap.ORIG_TRANSACTION, new byte[] {1}),
                Ber.tlv(Tcap.COMPONENT_PORTION, Ber.tlv(Tcap.INVOKE, concat(
                        Ber.integer(1),
                        Ber.oid(0, 4, 0, 0, 1, 0, 1, 1))))));

        assertThatThrownBy(() -> Tcap.parse(frame))
                .isInstanceOfSatisfying(MapException.class,
                        failure -> assertThat(failure.code()).isEqualTo("MAP_GLOBAL_OPCODE"));
    }

    @Test
    void aComponentTypeThisSideDoesNotProduceIsNamedInTheFailure() {
        byte[] frame = Ber.tlv(Tcap.BEGIN, concat(
                Ber.tlv(Tcap.ORIG_TRANSACTION, new byte[] {1}),
                Ber.tlv(Tcap.COMPONENT_PORTION, Ber.tlv(Tcap.REJECT,
                        Ber.tlv(Ber.context(0), new byte[] {1})))));

        assertThatThrownBy(() -> Tcap.parse(frame))
                .isInstanceOfSatisfying(MapException.class,
                        failure -> assertThat(failure.code()).isEqualTo("TCAP_UNSUPPORTED"));
    }

    @Test
    void somethingThatIsNotATransactionPortionIsRefused() {
        assertThatThrownBy(() -> Tcap.parse(Ber.octets("not tcap")))
                .isInstanceOf(BerException.class)
                .hasMessageContaining("transaction portion");
    }

    @Test
    void transactionIdsUseOneToFourOctets() {
        assertThat(Tcap.transactionId(1)).containsExactly((byte) 0x01);
        assertThat(Tcap.transactionId(0x0100)).containsExactly((byte) 0x01, (byte) 0x00);
        assertThat(Tcap.transactionId(0x010203))
                .containsExactly((byte) 0x01, (byte) 0x02, (byte) 0x03);
        assertThat(Tcap.transactionId(0x01020304))
                .containsExactly((byte) 0x01, (byte) 0x02, (byte) 0x03, (byte) 0x04);
        assertThat(Tcap.transactionIdValue(new byte[] {0x01, 0x02, 0x03, 0x04}))
                .isEqualTo(0x01020304);
    }

    @Test
    void aZeroOrOversizedTransactionIdIsRefused() {
        assertThatThrownBy(() -> Tcap.transactionId(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Tcap.transactionIdValue(new byte[0]))
                .isInstanceOf(BerException.class)
                .hasMessageContaining("one to four octets");
        assertThatThrownBy(() -> Tcap.transactionIdValue(new byte[5]))
                .isInstanceOf(BerException.class)
                .hasMessageContaining("one to four octets");
    }

    @Test
    void aMessageWithTwoComponentsIsNotSilentlyTakenAsOne() {
        Tcap.Message message = new Tcap.Message(Tcap.BEGIN, new byte[] {1}, null, null, null,
                List.of(new Tcap.Invoke(1, null, 85, null), new Tcap.Invoke(2, null, 85, null)));

        assertThatThrownBy(message::singleComponent)
                .isInstanceOfSatisfying(MapException.class,
                        failure -> assertThat(failure.code()).isEqualTo("TCAP_COMPONENT_COUNT"));
    }

    private static byte[] concat(byte[]... parts) {
        return Ber.concat(parts);
    }
}
