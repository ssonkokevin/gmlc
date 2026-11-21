package com.hamiltel.gmlc.ss7;

import com.hamiltel.gmlc.map.MapException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class M3uaTransportTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private static Sccp.UnitData request() {
        return new Sccp.UnitData(Sccp.Address.subsystem(Sccp.SSN_HLR),
                Sccp.Address.subsystem(3, Sccp.SSN_GMLC),
                new byte[] {0x62, 0x05, 0x48, 0x01, 0x01});
    }

    @Test
    void anExchangePerformsTheHandshakeCarriesTheMessageAndReturnsTheAnswer() throws Exception {
        AtomicReference<byte[]> delivered = new AtomicReference<>();
        try (M3uaTestPeer peer = new M3uaTestPeer(sccp -> {
            delivered.set(sccp);
            Sccp.UnitData incoming = Sccp.UnitData.parse(sccp);
            return new Sccp.UnitData(incoming.callingParty(), incoming.calledParty(),
                    new byte[] {0x64, 0x00}).encode();
        })) {
            Sccp.UnitData answer;
            try (TcpM3uaTransport transport = new TcpM3uaTransport(
                    "127.0.0.1", peer.port(), 3, 2, 7)) {
                answer = transport.exchange(2, request(), TIMEOUT);
            }

            assertThat(answer.data()).isEqualTo(new byte[] {0x64, 0x00});
            assertThat(answer.calledParty()).isEqualTo(request().callingParty());
            assertThat(answer.callingParty()).isEqualTo(request().calledParty());
            assertThat(delivered.get()).isEqualTo(request().encode());
            assertThat(peer.receivedSccp()).hasSize(1);
            assertThat(peer.awaitAspDown(5)).as("the client says ASP Down before it closes").isTrue();
            assertThat(peer.failure()).isNull();
        }
    }

    @Test
    void aGatewayThatDoesNotActivateTheAspIsRefusedBeforeAnyMessageIsSent() throws Exception {
        try (M3uaTestPeer peer = new M3uaTestPeer(sccp -> null, false);
             TcpM3uaTransport transport = new TcpM3uaTransport("127.0.0.1", peer.port(), 3, 2, 7)) {

            assertThatThrownBy(() -> transport.exchange(2, request(), TIMEOUT))
                    .isInstanceOf(MapException.class)
                    .satisfies(error -> assertThat(((MapException) error).code())
                            .isEqualTo("M3UA_HANDSHAKE"));
            assertThat(peer.receivedSccp()).isEmpty();
        }
    }

    @Test
    void anAnswerThatNeverComesIsATransportErrorNotABlankResult() throws Exception {
        try (M3uaTestPeer peer = new M3uaTestPeer(sccp -> null);
             TcpM3uaTransport transport = new TcpM3uaTransport("127.0.0.1", peer.port(), 3, 2, null)) {

            assertThatThrownBy(() -> transport.exchange(2, request(), Duration.ofMillis(200)))
                    .isInstanceOf(MapException.class)
                    .satisfies(error -> {
                        assertThat(((MapException) error).code()).isEqualTo("M3UA_TRANSPORT_ERROR");
                        assertThat(error.getMessage()).contains("timed out");
                    });
        }
    }

    @Test
    void aRequestWithoutADestinationPointCodeIsNotRouted() {
        try (TcpM3uaTransport transport = new TcpM3uaTransport("127.0.0.1", 9, 3, 2, null)) {
            assertThatThrownBy(() -> transport.exchange(0, request(), TIMEOUT))
                    .isInstanceOf(MapException.class)
                    .satisfies(error -> assertThat(((MapException) error).code())
                            .isEqualTo("SCCP_NO_ROUTE"));
        }
    }

    @Test
    void anUnconfiguredLinkRefusesEveryExchange() {
        assertThatThrownBy(() -> M3uaTransport.disabled("no STP").exchange(2, request(), TIMEOUT))
                .isInstanceOf(MapException.class)
                .satisfies(error -> assertThat(((MapException) error).code())
                        .isEqualTo("SIGNALLING_NOT_CONFIGURED"));
    }

    @Test
    void theCallingPartyIsTheGatewaysOwnPointCodeSubsystemAndOptionalNumber() {
        M3uaProperties withoutNumber = new M3uaProperties(
                "stp.lab.local", 2905, 0x1A2B, 0, -1, "", 2, 1000);
        assertThat(withoutNumber.configured()).isTrue();
        assertThat(withoutNumber.callingAddress())
                .isEqualTo(Sccp.Address.subsystem(0x1A2B, Sccp.SSN_GMLC));

        M3uaProperties withNumber = new M3uaProperties(
                "stp.lab.local", 2905, 0x1A2B, 0, 7, "25670000001", 2, 1000);
        assertThat(withNumber.callingAddress())
                .isEqualTo(new Sccp.Address(0x1A2B, Sccp.SSN_GMLC, "25670000001"));
        assertThat(withNumber.routingContextOrNull()).isEqualTo(7);

        M3uaProperties unconfigured = new M3uaProperties("", 0, 0, 0, -1, "", 2, 0);
        assertThat(unconfigured.configured()).isFalse();
        assertThat(unconfigured.port()).isEqualTo(2905);
        assertThat(unconfigured.timeoutMs()).isEqualTo(5000);
        assertThat(unconfigured.routingContextOrNull()).isNull();
    }

    @Test
    void aPointCodeWiderThanFourteenBitsIsRefused() {
        assertThatThrownBy(() -> new M3uaProperties("stp.lab.local", 2905, 0x4000, 0, -1, "", 2, 1000))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
