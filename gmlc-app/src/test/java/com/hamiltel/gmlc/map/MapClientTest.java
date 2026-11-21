package com.hamiltel.gmlc.map;

import com.hamiltel.gmlc.ss7.M3uaProperties;
import com.hamiltel.gmlc.ss7.Sccp;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The MAP client's dialogue shape: SRILCS and ATI go to the HLR, PSL goes to
 * the serving node by global title, a MAP error keeps its TS 29.002 name, and
 * an answer that belongs to another dialogue or operation is rejected rather
 * than read as a location.
 */
class MapClientTest {

    private static final String MLC = "256772000009";
    private static final MapProperties MAP = new MapProperties(MLC, 0);
    private static final M3uaProperties M3UA =
            new M3uaProperties("127.0.0.1", 2905, 1001, 1002, -1, "", 2, 2000);

    private static Tcap.Component result(int opCode, byte[] argument) {
        return new Tcap.ReturnResult(1, opCode, argument);
    }

    @Test
    void aRoutingEnquiryIsASRILCSToTheHlrAndTheAnswerIsDecoded() {
        MapTestLink link = new MapTestLink().answer(
                MapLcs.OP_SEND_ROUTING_INFO_FOR_LCS,
                (dialogue, invoke) -> result(MapLcs.OP_SEND_ROUTING_INFO_FOR_LCS,
                        MapTestAnswers.routing("256772000002", false, null)));

        MapLcs.RoutingInfo info =
                new MapClient(link, MAP, M3UA).routeInfo(MapTestAnswers.IMSI, null);

        assertThat(info.networkNode()).isEqualTo("256772000002");
        assertThat(info.gprsNode()).isFalse();
        assertThat(info.mmeName()).isNull();

        Tcap.Message begin = Tcap.parse(link.requests.get(0).data());
        assertThat(begin.type()).isEqualTo(Tcap.BEGIN);
        assertThat(begin.applicationContext()).containsExactly(0, 4, 0, 0, 1, 0, 37, 3);
        assertThat(link.invokes.get(0).opCode())
                .isEqualTo(MapLcs.OP_SEND_ROUTING_INFO_FOR_LCS);
        assertThat(link.invokes.get(0).argument())
                .isEqualTo(MapLcs.routingInfoForLcs(MLC, MapTestAnswers.IMSI, null));

        Sccp.UnitData request = link.requests.get(0);
        assertThat(request.calledParty().pointCode()).isZero();
        assertThat(request.calledParty().subsystem()).isEqualTo(Sccp.SSN_HLR);
        assertThat(request.calledParty().globalTitle()).isNull();
        assertThat(request.callingParty().pointCode()).isEqualTo(1001);
        assertThat(request.callingParty().subsystem()).isEqualTo(Sccp.SSN_GMLC);
        assertThat(link.destinationPointCodes.get(0)).isEqualTo(1002);
    }

    @Test
    void aConfiguredHlrPointCodeIsTheDestinationAndTheCalledPointCode() {
        MapTestLink link = new MapTestLink().answer(
                MapLcs.OP_SEND_ROUTING_INFO_FOR_LCS,
                (dialogue, invoke) -> result(MapLcs.OP_SEND_ROUTING_INFO_FOR_LCS,
                        MapTestAnswers.routing("256772000002", false, null)));

        new MapClient(link, new MapProperties(MLC, 2001), M3UA)
                .routeInfo(MapTestAnswers.IMSI, null);

        assertThat(link.destinationPointCodes.get(0)).isEqualTo(2001);
        assertThat(link.requests.get(0).calledParty().pointCode()).isEqualTo(2001);
        assertThat(link.requests.get(0).calledParty().subsystem()).isEqualTo(Sccp.SSN_HLR);
    }

    @Test
    void aProvideLocationIsAddressedByGlobalTitleAtTheServingNode() {
        MapTestLink link = new MapTestLink().answer(
                MapLcs.OP_PROVIDE_SUBSCRIBER_LOCATION,
                (dialogue, invoke) -> result(MapLcs.OP_PROVIDE_SUBSCRIBER_LOCATION,
                        MapTestAnswers.provideLocation(45, MapTestAnswers.GSM_CGI)));

        MapLcs.ProvideSubscriberLocationResult answer =
                new MapClient(link, MAP, M3UA).provideLocation("256772000002", true,
                        MapTestAnswers.IMSI, null, 100, "gmlc");

        assertThat(answer.cell().cellCode()).isEqualTo(1234);
        Sccp.UnitData request = link.requests.get(0);
        assertThat(request.calledParty().globalTitle()).isEqualTo("256772000002");
        assertThat(request.calledParty().subsystem()).isEqualTo(Sccp.SSN_SGSN);
        assertThat(link.destinationPointCodes.get(0)).isEqualTo(1002);
        assertThat(link.invokes.get(0).argument()).isEqualTo(MapLcs.provideSubscriberLocation(
                MLC, MapTestAnswers.IMSI, null, 100, "gmlc"));
    }

    @Test
    void aMapErrorKeepsTheSpecificationName() {
        MapTestLink link = new MapTestLink().answer(MapLcs.OP_SEND_ROUTING_INFO_FOR_LCS,
                (dialogue, invoke) -> new Tcap.ReturnError(1, 53, null));

        assertThatThrownBy(() -> new MapClient(link, MAP, M3UA)
                .routeInfo(MapTestAnswers.IMSI, null))
                .isInstanceOf(MapException.class)
                .satisfies(error -> assertThat(((MapException) error).code())
                        .isEqualTo("UNAUTHORIZED_LCS_CLIENT"));
    }

    @Test
    void aBlankMlcNumberFailsClosedWithoutTouchingTheLink() {
        MapTestLink link = new MapTestLink();

        assertThatThrownBy(() -> new MapClient(link, new MapProperties("", 0), M3UA)
                .routeInfo(MapTestAnswers.IMSI, null))
                .isInstanceOf(MapException.class)
                .satisfies(error -> assertThat(((MapException) error).code())
                        .isEqualTo("SIGNALLING_NOT_CONFIGURED"));
        assertThat(link.exchanges).isZero();
    }

    @Test
    void anAnswerForAnotherDialogueIsRejected() {
        MapTestLink link = new MapTestLink().raw(MapLcs.OP_SEND_ROUTING_INFO_FOR_LCS,
                (dialogue, invoke) -> {
                    int transactionId = dialogue.transactionIdValue();
                    int other = transactionId == 1 ? 2 : 1;
                    return Tcap.message(Tcap.END, transactionId, other, null,
                            result(MapLcs.OP_SEND_ROUTING_INFO_FOR_LCS,
                                    MapTestAnswers.routing("256772000002", false, null)), false);
                });

        assertThatThrownBy(() -> new MapClient(link, MAP, M3UA)
                .routeInfo(MapTestAnswers.IMSI, null))
                .isInstanceOf(MapException.class)
                .satisfies(error -> assertThat(((MapException) error).code())
                        .isEqualTo("TCAP_TRANSACTION_MISMATCH"));
    }

    @Test
    void anAnswerInAnotherApplicationContextIsRejected() {
        MapTestLink link = new MapTestLink().raw(MapLcs.OP_SEND_ROUTING_INFO_FOR_LCS,
                (dialogue, invoke) -> {
                    int transactionId = dialogue.transactionIdValue();
                    return Tcap.message(Tcap.END, transactionId, transactionId,
                            MapLcs.AC_ANY_TIME_ENQUIRY,
                            result(MapLcs.OP_SEND_ROUTING_INFO_FOR_LCS,
                                    MapTestAnswers.routing("256772000002", false, null)), false);
                });

        assertThatThrownBy(() -> new MapClient(link, MAP, M3UA)
                .routeInfo(MapTestAnswers.IMSI, null))
                .isInstanceOf(MapException.class)
                .satisfies(error -> assertThat(((MapException) error).code())
                        .isEqualTo("TCAP_APPLICATION_CONTEXT"));
    }

    @Test
    void aReturnResultForAnotherOperationIsRejected() {
        MapTestLink link = new MapTestLink().answer(MapLcs.OP_SEND_ROUTING_INFO_FOR_LCS,
                (dialogue, invoke) -> new Tcap.ReturnResult(1, 99,
                        MapTestAnswers.routing("256772000002", false, null)));

        assertThatThrownBy(() -> new MapClient(link, MAP, M3UA)
                .routeInfo(MapTestAnswers.IMSI, null))
                .isInstanceOf(MapException.class)
                .satisfies(error -> assertThat(((MapException) error).code())
                        .isEqualTo("TCAP_UNEXPECTED_COMPONENT"));
    }
}
