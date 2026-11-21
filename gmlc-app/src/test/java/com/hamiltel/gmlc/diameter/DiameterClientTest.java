package com.hamiltel.gmlc.diameter;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiameterClientTest {

    private static final DiameterProperties PROPERTIES =
            new DiameterProperties("gmlc.lab.local", "lab.local", "127.0.0.1", 3868, 2000);

    private static DiameterProperties onPort(int port) {
        return new DiameterProperties("gmlc.lab.local", "lab.local", "127.0.0.1", port, 2000);
    }

    private static List<Avp> success(Avp... extra) {
        List<Avp> avps = new java.util.ArrayList<>();
        avps.add(Avp.baseU32(DiameterDictionary.RESULT_CODE, DiameterDictionary.DIAMETER_SUCCESS));
        avps.addAll(List.of(extra));
        return avps;
    }

    @Test
    void routeInfoAsksTheHssAndReturnsTheMmeItNames() throws Exception {
        try (DiameterTestPeer peer = new DiameterTestPeer(request -> success(
                Avp.vendorGrouped(DiameterDictionary.SERVING_NODE,
                        Avp.vendorUtf8(DiameterDictionary.MME_NAME, "mme1.epc.mnc001.mcc001.3gppnetwork.org"),
                        Avp.vendorUtf8(DiameterDictionary.MME_REALM, "epc.mnc001.mcc001.3gppnetwork.org"))));
             TcpDiameterTransport transport = new TcpDiameterTransport(
                     "127.0.0.1", peer.port(), PROPERTIES.originHost(), PROPERTIES.originRealm(), "test")) {

            Optional<ServingNode> node = new SlhClient(transport, onPort(peer.port()))
                    .routeInfo(new SubscriberId("001010000000001", "256700000001"));

            assertThat(node).hasValueSatisfying(found -> {
                assertThat(found.diameterName()).isEqualTo("mme1.epc.mnc001.mcc001.3gppnetwork.org");
                assertThat(found.realm()).isEqualTo("epc.mnc001.mcc001.3gppnetwork.org");
            });

            DiameterMessage request = peer.received().get(0);
            assertThat(request.commandCode()).isEqualTo(DiameterDictionary.LCS_ROUTING_INFO);
            assertThat(request.applicationId()).isEqualTo(DiameterDictionary.SLH_APPLICATION_ID);
            assertThat(request.text(DiameterDictionary.USER_NAME)).isEqualTo("001010000000001");
            assertThat(request.first(DiameterDictionary.MSISDN).orElseThrow().copyOfValue())
                    .isEqualTo(new byte[] {0x52, 0x76, 0x00, 0x00, 0x00, 0x10});
            assertThat(request.integer(DiameterDictionary.AUTH_SESSION_STATE)).isEqualTo(1);
            assertThat(request.text(DiameterDictionary.ORIGIN_HOST)).isEqualTo("gmlc.lab.local");
            assertThat(request.hopByHopId()).isNotZero();
            assertThat(peer.failure()).isNull();
        }
    }

    @Test
    void anUnknownSubscriberIsReportedAsSuchAndNotAsAnEmptyAnswer() throws Exception {
        try (DiameterTestPeer peer = new DiameterTestPeer(request -> List.of(
                Avp.vendorGrouped(DiameterDictionary.EXPERIMENTAL_RESULT,
                        Avp.baseU32(DiameterDictionary.VENDOR_ID, Avp.THREE_GPP),
                        Avp.baseU32(DiameterDictionary.EXPERIMENTAL_RESULT_CODE,
                                DiameterDictionary.DIAMETER_USER_UNKNOWN))));
             TcpDiameterTransport transport = new TcpDiameterTransport(
                     "127.0.0.1", peer.port(), PROPERTIES.originHost(), PROPERTIES.originRealm(), "test")) {

            assertThatThrownBy(() -> new SlhClient(transport, onPort(peer.port()))
                    .routeInfo(new SubscriberId("001010000000001", null)))
                    .isInstanceOf(SignallingException.class)
                    .satisfies(error -> assertThat(((SignallingException) error).code())
                            .isEqualTo("SUBSCRIBER_UNKNOWN"));
        }
    }

    @Test
    void aSuccessfulAnswerWithoutAnAddressableNodeIsEmptyNotInvented() throws Exception {
        try (DiameterTestPeer peer = new DiameterTestPeer(request -> success(
                Avp.vendorGrouped(DiameterDictionary.SERVING_NODE,
                        Avp.vendor(DiameterDictionary.MSC_NUMBER, new byte[] {0x62, 0x75}))));
             TcpDiameterTransport transport = new TcpDiameterTransport(
                     "127.0.0.1", peer.port(), PROPERTIES.originHost(), PROPERTIES.originRealm(), "test")) {

            assertThat(new SlhClient(transport, onPort(peer.port()))
                    .routeInfo(new SubscriberId(null, "256700000001")))
                    .isEmpty();
        }
    }

    @Test
    void provideLocationParsesTheEcgiIntoAnLteCell() throws Exception {
        byte[] ecgi = new byte[] {0x00, (byte) 0xF1, 0x10, 0x00, 0x0A, 0x1B, 0x01};
        try (DiameterTestPeer peer = new DiameterTestPeer(request -> success(
                Avp.vendor(DiameterDictionary.ECGI, ecgi),
                Avp.vendorU32(DiameterDictionary.ACCURACY_FULFILMENT_INDICATOR,
                        DiameterDictionary.ACCURACY_FULFILLED),
                Avp.vendorU32(DiameterDictionary.AGE_OF_LOCATION_ESTIMATE, 45)));
             TcpDiameterTransport transport = new TcpDiameterTransport(
                     "127.0.0.1", peer.port(), PROPERTIES.originHost(), PROPERTIES.originRealm(), "test")) {

            Optional<ServingCell> cell = new SlgClient(transport, onPort(peer.port()))
                    .provideLocation(ServingNode.of("mme1.epc.mnc001.mcc001.3gppnetwork.org"),
                            new SubscriberId("001010000000001", null),
                            100, "ops-console", true);

            assertThat(cell).hasValueSatisfying(found -> {
                assertThat(found.rat()).isEqualTo("LTE");
                assertThat(found.mcc()).isEqualTo("001");
                assertThat(found.mnc()).isEqualTo("01");
                assertThat(found.areaCode()).isNull();
                assertThat(found.cellCode()).isEqualTo("662273");
                assertThat(found.accuracyFulfilled()).isTrue();
                assertThat(found.ageSeconds()).isEqualTo(45);
            });

            DiameterMessage plr = peer.received().get(0);
            assertThat(plr.commandCode()).isEqualTo(DiameterDictionary.PROVIDE_LOCATION);
            assertThat(plr.applicationId()).isEqualTo(DiameterDictionary.SLG_APPLICATION_ID);
            assertThat(plr.text(DiameterDictionary.DESTINATION_HOST))
                    .isEqualTo("mme1.epc.mnc001.mcc001.3gppnetwork.org");
            assertThat(plr.text(DiameterDictionary.DESTINATION_REALM))
                    .isEqualTo("epc.mnc001.mcc001.3gppnetwork.org");
            assertThat(plr.integer(DiameterDictionary.SLG_LOCATION_TYPE)).isEqualTo(1);
            assertThat(plr.integer(DiameterDictionary.LCS_CLIENT_TYPE))
                    .isEqualTo(DiameterDictionary.LCS_CLIENT_TYPE_EMERGENCY);
            assertThat(plr.first(DiameterDictionary.LCS_EPS_CLIENT_NAME).orElseThrow().children())
                    .extracting(Avp::code)
                    .contains(DiameterDictionary.LCS_NAME_STRING);
            Avp qos = plr.first(DiameterDictionary.LCS_QOS).orElseThrow();
            assertThat(qos.children()).extracting(Avp::code)
                    .contains(DiameterDictionary.HORIZONTAL_ACCURACY);
            int code = qos.children().stream()
                    .filter(avp -> avp.code() == DiameterDictionary.HORIZONTAL_ACCURACY)
                    .findFirst().orElseThrow().asInt();
            assertThat(UncertaintyCode.radiusMetres(code)).isGreaterThanOrEqualTo(100);
        }
    }

    @Test
    void provideLocationParsesAGsmCgiWithItsAreaCode() throws Exception {
        byte[] cgi = new byte[] {0x00, (byte) 0xF1, 0x10, 0x01, 0x01, 0x00, 0x01};
        try (DiameterTestPeer peer = new DiameterTestPeer(request -> success(
                Avp.vendor(DiameterDictionary.CELL_GLOBAL_IDENTITY, cgi)));
             TcpDiameterTransport transport = new TcpDiameterTransport(
                     "127.0.0.1", peer.port(), PROPERTIES.originHost(), PROPERTIES.originRealm(), "test")) {

            assertThat(new SlgClient(transport, onPort(peer.port()))
                    .provideLocation(ServingNode.of("msc1.lab.local"),
                            new SubscriberId(null, "256700000001"), 0, "ops-console", false))
                    .hasValueSatisfying(found -> {
                        assertThat(found.rat()).isEqualTo("GSM");
                        assertThat(found.areaCode()).isEqualTo("257");
                        assertThat(found.cellCode()).isEqualTo("1");
                        assertThat(found.accuracyFulfilled()).isNull();
                    });
        }
    }

    @Test
    void aPositioningFailureIsASignallingFailureNotAnEmptyCell() throws Exception {
        try (DiameterTestPeer peer = new DiameterTestPeer(request -> List.of(
                Avp.vendorGrouped(DiameterDictionary.EXPERIMENTAL_RESULT,
                        Avp.baseU32(DiameterDictionary.VENDOR_ID, Avp.THREE_GPP),
                        Avp.baseU32(DiameterDictionary.EXPERIMENTAL_RESULT_CODE,
                                DiameterDictionary.DIAMETER_POSITIONING_FAILED))));
             TcpDiameterTransport transport = new TcpDiameterTransport(
                     "127.0.0.1", peer.port(), PROPERTIES.originHost(), PROPERTIES.originRealm(), "test")) {

            assertThatThrownBy(() -> new SlgClient(transport, onPort(peer.port()))
                    .provideLocation(ServingNode.of("mme1.lab.local"),
                            new SubscriberId("001010000000001", null), 100, "ops-console", false))
                    .isInstanceOf(SignallingException.class)
                    .satisfies(error -> assertThat(((SignallingException) error).code())
                            .isEqualTo("POSITIONING_FAILED"));
        }
    }

    @Test
    void aPlainSuccessWithoutACellIsEmpty() throws Exception {
        try (DiameterTestPeer peer = new DiameterTestPeer(request -> success());
             TcpDiameterTransport transport = new TcpDiameterTransport(
                     "127.0.0.1", peer.port(), PROPERTIES.originHost(), PROPERTIES.originRealm(), "test")) {

            assertThat(new SlgClient(transport, onPort(peer.port()))
                    .provideLocation(ServingNode.of("mme1.lab.local"),
                            new SubscriberId("001010000000001", null), 100, "ops-console", false))
                    .isEmpty();
        }
    }

    @Test
    void aPeerWithSpareEcgiBitsIsRejectedRatherThanMisread() throws Exception {
        byte[] ecgi = new byte[] {0x00, (byte) 0xF1, 0x10, 0x10, 0x0A, 0x1B, 0x01};
        try (DiameterTestPeer peer = new DiameterTestPeer(request -> success(
                Avp.vendor(DiameterDictionary.ECGI, ecgi)));
             TcpDiameterTransport transport = new TcpDiameterTransport(
                     "127.0.0.1", peer.port(), PROPERTIES.originHost(), PROPERTIES.originRealm(), "test")) {

            assertThatThrownBy(() -> new SlgClient(transport, onPort(peer.port()))
                    .provideLocation(ServingNode.of("mme1.lab.local"),
                            new SubscriberId("001010000000001", null), 100, "ops-console", false))
                    .isInstanceOf(SignallingException.class)
                    .hasMessageContaining("spare bits");
        }
    }

    @Test
    void aDisabledTransportFailsClosed() {
        assertThatThrownBy(() -> DiameterTransport.disabled("not configured")
                .exchange(DiameterMessage.request(1, 0, List.of()), java.time.Duration.ofSeconds(1)))
                .isInstanceOf(SignallingException.class)
                .satisfies(error -> assertThat(((SignallingException) error).code())
                        .isEqualTo("SIGNALLING_NOT_CONFIGURED"));
    }
}
