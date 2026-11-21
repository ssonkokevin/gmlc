package com.hamiltel.gmlc.location;

import com.hamiltel.gmlc.cell.CellRepository;
import com.hamiltel.gmlc.diameter.Avp;
import com.hamiltel.gmlc.diameter.DiameterDictionary;
import com.hamiltel.gmlc.diameter.DiameterProperties;
import com.hamiltel.gmlc.diameter.DiameterTestPeer;
import com.hamiltel.gmlc.diameter.SignallingException;
import com.hamiltel.gmlc.diameter.SlgClient;
import com.hamiltel.gmlc.diameter.SlhClient;
import com.hamiltel.gmlc.diameter.TcpDiameterTransport;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The locator against a loopback HSS and MME: the cell the network names is
 * resolved in the plan, and every way that can fail is a stable code rather
 * than a made-up position.
 */
class DiameterCellLocatorTest {

    private static final byte[] ECGI_001_01_662273 =
            new byte[] {0x00, (byte) 0xF1, 0x10, 0x00, 0x0A, 0x1B, 0x01};

    private static List<Avp> routingInfoAnswer() {
        return List.of(
                Avp.baseU32(DiameterDictionary.RESULT_CODE, DiameterDictionary.DIAMETER_SUCCESS),
                Avp.vendorGrouped(DiameterDictionary.SERVING_NODE,
                        Avp.vendorUtf8(DiameterDictionary.MME_NAME,
                                "mme1.epc.mnc001.mcc001.3gppnetwork.org"),
                        Avp.vendorUtf8(DiameterDictionary.MME_REALM,
                                "epc.mnc001.mcc001.3gppnetwork.org")));
    }

    private static List<Avp> locationAnswer(Boolean fulfilled, Integer age) {
        List<Avp> avps = new java.util.ArrayList<>();
        avps.add(Avp.baseU32(DiameterDictionary.RESULT_CODE, DiameterDictionary.DIAMETER_SUCCESS));
        avps.add(Avp.vendor(DiameterDictionary.ECGI, ECGI_001_01_662273));
        if (fulfilled != null) {
            avps.add(Avp.vendorU32(DiameterDictionary.ACCURACY_FULFILMENT_INDICATOR,
                    fulfilled ? DiameterDictionary.ACCURACY_FULFILLED
                              : DiameterDictionary.ACCURACY_NOT_FULFILLED));
        }
        if (age != null) {
            avps.add(Avp.vendorU32(DiameterDictionary.AGE_OF_LOCATION_ESTIMATE, age));
        }
        return avps;
    }

    private static CellRepository.CellRow cell(Integer radius, Integer quality) {
        return new CellRepository.CellRow(1, "LTE", "001-01-1-662273", "001", "01", "1",
                "662273", "Kampala Central", new BigDecimal("0.3476000"),
                new BigDecimal("32.5825000"), new BigDecimal("18.0"), 120, 65, radius, null,
                101, 1650, "MACRO", "drive-test", quality, LocalDate.now().minusMonths(2),
                java.time.Instant.now());
    }

    private LocationLocator.Query query() {
        return new LocationLocator.Query(1, "256700000001", "001010000000001", 100,
                "TEST", "hamiltel-gmlc");
    }

    private static SlhClient slh(DiameterTestPeer peer, DiameterProperties properties) {
        return new SlhClient(transport(peer, properties), properties);
    }

    private static SlgClient slg(DiameterTestPeer peer, DiameterProperties properties) {
        return new SlgClient(transport(peer, properties), properties);
    }

    private static TcpDiameterTransport transport(DiameterTestPeer peer,
                                                  DiameterProperties properties) {
        return new TcpDiameterTransport("127.0.0.1", peer.port(), properties.originHost(),
                properties.originRealm(), "test");
    }

    private static DiameterProperties properties(int port) {
        return new DiameterProperties("gmlc.lab.local", "lab.local", "127.0.0.1", port, 2000);
    }

    @Test
    void answersWithThePlanCircleAndItsEnvelope() throws Exception {
        try (DiameterTestPeer peer = new DiameterTestPeer(request ->
                request.commandCode() == DiameterDictionary.LCS_ROUTING_INFO
                        ? routingInfoAnswer()
                        : locationAnswer(true, 45))) {
            DiameterProperties properties = properties(peer.port());
            DiameterCellLocator locator = new DiameterCellLocator(
                    slh(peer, properties), slg(peer, properties),
                    (tenantId, rat, mcc, mnc, code) -> Optional.of(cell(2500, 80)));

            LocationEstimate estimate = locator.locate(query()).orElseThrow();

            assertThat(estimate.rat()).isEqualTo("LTE");
            assertThat(estimate.method()).isEqualTo("CELL_ID");
            assertThat(estimate.shape()).isEqualTo("CIRCLE");
            assertThat(estimate.latitude()).isEqualByComparingTo("0.3476");
            assertThat(estimate.longitude()).isEqualByComparingTo("32.5825");
            assertThat(estimate.uncertaintyM()).isEqualTo(2500);
            assertThat(estimate.confidence()).isEqualTo(80);
            assertThat(estimate.ageSeconds()).isEqualTo(45);
            assertThat(estimate.accuracyFulfilled()).isFalse();
            assertThat(estimate.servingCell()).isEqualTo("001-01-1-662273");
            assertThat(estimate.engine()).isEqualTo("CELL_DB");
            assertThat(estimate.positioningNode()).isEqualTo("mme1.epc.mnc001.mcc001.3gppnetwork.org");
            assertThat(estimate.methodsAttempted()).containsExactly("SLH_RIR", "SLG_PLA");
        }
    }

    @Test
    void anUnqualifiedCellGetsNeutralConfidenceNotAHighOne() throws Exception {
        try (DiameterTestPeer peer = new DiameterTestPeer(request ->
                request.commandCode() == DiameterDictionary.LCS_ROUTING_INFO
                        ? routingInfoAnswer()
                        : locationAnswer(null, null))) {
            DiameterProperties properties = properties(peer.port());
            DiameterCellLocator locator = new DiameterCellLocator(
                    slh(peer, properties), slg(peer, properties),
                    (tenantId, rat, mcc, mnc, code) -> Optional.of(cell(5000, null)));

            LocationEstimate estimate = locator.locate(query()).orElseThrow();

            assertThat(estimate.confidence()).isEqualTo(50);
            assertThat(estimate.geometryDataQuality()).isNull();
            assertThat(estimate.ageSeconds()).isZero();
        }
    }

    @Test
    void aCellMissingFromThePlanIsAFailureNotAnApproximation() throws Exception {
        try (DiameterTestPeer peer = new DiameterTestPeer(request ->
                request.commandCode() == DiameterDictionary.LCS_ROUTING_INFO
                        ? routingInfoAnswer()
                        : locationAnswer(true, 0))) {
            DiameterProperties properties = properties(peer.port());
            DiameterCellLocator locator = new DiameterCellLocator(
                    slh(peer, properties), slg(peer, properties),
                    (tenantId, rat, mcc, mnc, code) -> Optional.empty());

            assertThatThrownBy(() -> locator.locate(query()))
                    .isInstanceOf(SignallingException.class)
                    .satisfies(error -> assertThat(((SignallingException) error).code())
                            .isEqualTo("CELL_NOT_IN_PLAN"));
        }
    }

    @Test
    void aCellWithoutRadiusHasNoHonestUncertainty() throws Exception {
        try (DiameterTestPeer peer = new DiameterTestPeer(request ->
                request.commandCode() == DiameterDictionary.LCS_ROUTING_INFO
                        ? routingInfoAnswer()
                        : locationAnswer(true, 0))) {
            DiameterProperties properties = properties(peer.port());
            DiameterCellLocator locator = new DiameterCellLocator(
                    slh(peer, properties), slg(peer, properties),
                    (tenantId, rat, mcc, mnc, code) -> Optional.of(cell(null, 80)));

            assertThatThrownBy(() -> locator.locate(query()))
                    .isInstanceOf(SignallingException.class)
                    .satisfies(error -> assertThat(((SignallingException) error).code())
                            .isEqualTo("CELL_WITHOUT_RADIUS"));
        }
    }

    @Test
    void anHssWithoutAnAddressableNodeIsAFailure() throws Exception {
        try (DiameterTestPeer peer = new DiameterTestPeer(request ->
                List.of(Avp.baseU32(DiameterDictionary.RESULT_CODE,
                        DiameterDictionary.DIAMETER_SUCCESS)))) {
            DiameterProperties properties = properties(peer.port());
            DiameterCellLocator locator = new DiameterCellLocator(
                    slh(peer, properties), slg(peer, properties),
                    (tenantId, rat, mcc, mnc, code) -> Optional.of(cell(2500, 80)));

            assertThatThrownBy(() -> locator.locate(query()))
                    .isInstanceOf(SignallingException.class)
                    .satisfies(error -> assertThat(((SignallingException) error).code())
                            .isEqualTo("NO_SERVING_NODE"));
        }
    }

    @Test
    void aSuccessfulAnswerWithNoCellIsEmpty() throws Exception {
        try (DiameterTestPeer peer = new DiameterTestPeer(request ->
                request.commandCode() == DiameterDictionary.LCS_ROUTING_INFO
                        ? routingInfoAnswer()
                        : List.of(Avp.baseU32(DiameterDictionary.RESULT_CODE,
                                DiameterDictionary.DIAMETER_SUCCESS)))) {
            DiameterProperties properties = properties(peer.port());
            DiameterCellLocator locator = new DiameterCellLocator(
                    slh(peer, properties), slg(peer, properties),
                    (tenantId, rat, mcc, mnc, code) -> Optional.of(cell(2500, 80)));

            assertThat(locator.locate(query())).isEmpty();
        }
    }
}
