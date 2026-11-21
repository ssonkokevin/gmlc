package com.hamiltel.gmlc.map;

import com.hamiltel.gmlc.cell.CellRepository;
import com.hamiltel.gmlc.cell.CellSelector;
import com.hamiltel.gmlc.diameter.SignallingException;
import com.hamiltel.gmlc.location.LocationEstimate;
import com.hamiltel.gmlc.location.LocationLocator;
import com.hamiltel.gmlc.location.MapCellLocator;
import com.hamiltel.gmlc.ss7.M3uaProperties;
import com.hamiltel.gmlc.ss7.Sccp;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The MAP locator end to end over the codec: SRILCS finds the serving node,
 * PSL or ATI names the cell, and only the operator's plan can turn that cell
 * into a position. Every failure is a stable code, never a guessed circle.
 */
class MapCellLocatorTest {

    private static final String MLC = "256772000009";
    private static final MapProperties MAP = new MapProperties(MLC, 0);
    private static final M3uaProperties M3UA =
            new M3uaProperties("127.0.0.1", 2905, 1001, 1002, -1, "", 2, 2000);

    private static LocationLocator.Query query(Integer accuracyMeters) {
        return new LocationLocator.Query(1, MapTestAnswers.MSISDN, MapTestAnswers.IMSI,
                accuracyMeters, "TEST", "gmlc");
    }

    private static MapCellLocator locator(MapTestLink link, CellSelector cells) {
        return new MapCellLocator(new MapClient(link, MAP, M3UA), cells);
    }

    private static Tcap.Component result(int opCode, byte[] argument) {
        return new Tcap.ReturnResult(1, opCode, argument);
    }

    @Test
    void aCsAnswerLandsOnThePlanCellWithItsHonestyEnvelope() {
        MapTestLink link = new MapTestLink()
                .answer(MapLcs.OP_SEND_ROUTING_INFO_FOR_LCS,
                        (dialogue, invoke) -> result(MapLcs.OP_SEND_ROUTING_INFO_FOR_LCS,
                                MapTestAnswers.routing("256772000002", false, null)))
                .answer(MapLcs.OP_PROVIDE_SUBSCRIBER_LOCATION,
                        (dialogue, invoke) -> result(MapLcs.OP_PROVIDE_SUBSCRIBER_LOCATION,
                                MapTestAnswers.provideLocation(45, MapTestAnswers.GSM_CGI)));
        PlanCells plan = new PlanCells().put("GSM", "4660", "1234",
                cell("GSM", "001-01-4660-1234", 2500, 80));

        LocationEstimate estimate = locator(link, plan).locate(query(3000)).orElseThrow();

        assertThat(estimate.rat()).isEqualTo("GSM");
        assertThat(estimate.method()).isEqualTo("CELL_ID");
        assertThat(estimate.shape()).isEqualTo("CIRCLE");
        assertThat(estimate.latitude()).isEqualByComparingTo("0.3476");
        assertThat(estimate.longitude()).isEqualByComparingTo("32.5825");
        assertThat(estimate.uncertaintyM()).isEqualTo(2500);
        assertThat(estimate.confidence()).isEqualTo(80);
        assertThat(estimate.geometryDataQuality()).isEqualTo(80);
        assertThat(estimate.ageSeconds()).isEqualTo(45 * 60);
        assertThat(estimate.accuracyFulfilled()).isTrue();
        assertThat(estimate.servingCell()).isEqualTo("001-01-4660-1234");
        assertThat(estimate.engine()).isEqualTo("CELL_DB");
        assertThat(estimate.positioningNode()).isEqualTo("256772000002");
        assertThat(estimate.methodsAttempted()).containsExactly("MAP_SRILCS", "MAP_PSL");

        Sccp.UnitData psl = link.requests.get(1);
        assertThat(psl.calledParty().globalTitle()).isEqualTo("256772000002");
        assertThat(psl.calledParty().subsystem()).isEqualTo(Sccp.SSN_MSC);
        assertThat(psl.callingParty().subsystem()).isEqualTo(Sccp.SSN_GMLC);
        assertThat(link.invokes.get(1).argument()).isEqualTo(MapLcs.provideSubscriberLocation(
                MLC, MapTestAnswers.IMSI, MapTestAnswers.MSISDN, 3000, "gmlc"));
    }

    @Test
    void aPsAnswerIsAskedOfTheSgsnAndResolvesTheUmtsRow() {
        MapTestLink link = new MapTestLink()
                .answer(MapLcs.OP_SEND_ROUTING_INFO_FOR_LCS,
                        (dialogue, invoke) -> result(MapLcs.OP_SEND_ROUTING_INFO_FOR_LCS,
                                MapTestAnswers.routing("256772000003", true, null)))
                .answer(MapLcs.OP_PROVIDE_SUBSCRIBER_LOCATION,
                        (dialogue, invoke) -> result(MapLcs.OP_PROVIDE_SUBSCRIBER_LOCATION,
                                MapTestAnswers.provideLocation(0, MapTestAnswers.GSM_CGI)));
        PlanCells plan = new PlanCells().put("UMTS", "4660", "1234",
                cell("UMTS", "001-01-4660-1234", 1200, 90));

        Optional<LocationEstimate> estimate = locator(link, plan).locate(query(100));

        assertThat(estimate).isPresent();
        assertThat(estimate.orElseThrow().rat()).isEqualTo("UMTS");
        assertThat(link.requests.get(1).calledParty().subsystem()).isEqualTo(Sccp.SSN_SGSN);
        assertThat(estimate.orElseThrow().accuracyFulfilled()).isFalse();
    }

    @Test
    void anEpsSubscriberIsAnsweredByAnAnyTimeEnquiry() {
        MapTestLink link = new MapTestLink()
                .answer(MapLcs.OP_SEND_ROUTING_INFO_FOR_LCS,
                        (dialogue, invoke) -> result(MapLcs.OP_SEND_ROUTING_INFO_FOR_LCS,
                                MapTestAnswers.routing(null, false,
                                        "mme1.epc.mnc001.mcc001.3gppnetwork.org")))
                .answer(MapLcs.OP_ANY_TIME_INTERROGATION,
                        (dialogue, invoke) -> result(MapLcs.OP_ANY_TIME_INTERROGATION,
                                MapTestAnswers.anyTimeEps(2, MapTestAnswers.LTE_CGI,
                                        MapTestAnswers.LTE_TAI)));
        PlanCells plan = new PlanCells().put("LTE", "1", "662273",
                cell("LTE", "001-01-1-662273", 900, 70));

        LocationEstimate estimate = locator(link, plan).locate(query(1000)).orElseThrow();

        assertThat(estimate.rat()).isEqualTo("LTE");
        assertThat(estimate.ageSeconds()).isEqualTo(120);
        assertThat(estimate.positioningNode())
                .isEqualTo("mme1.epc.mnc001.mcc001.3gppnetwork.org");
        assertThat(estimate.methodsAttempted()).containsExactly("MAP_SRILCS", "MAP_ATI");
    }

    @Test
    void aCircleWithoutACellIsNoSourceRatherThanAnInventedPosition() {
        MapTestLink link = new MapTestLink()
                .answer(MapLcs.OP_SEND_ROUTING_INFO_FOR_LCS,
                        (dialogue, invoke) -> result(MapLcs.OP_SEND_ROUTING_INFO_FOR_LCS,
                                MapTestAnswers.routing("256772000002", false, null)))
                .answer(MapLcs.OP_PROVIDE_SUBSCRIBER_LOCATION,
                        (dialogue, invoke) -> result(MapLcs.OP_PROVIDE_SUBSCRIBER_LOCATION,
                                MapTestAnswers.provideLocation(10, null)));

        assertThat(locator(link, new PlanCells()).locate(query(100))).isEmpty();
    }

    @Test
    void aCellMissingFromThePlanIsAFailureNotAnApproximation() {
        MapTestLink link = new MapTestLink()
                .answer(MapLcs.OP_SEND_ROUTING_INFO_FOR_LCS,
                        (dialogue, invoke) -> result(MapLcs.OP_SEND_ROUTING_INFO_FOR_LCS,
                                MapTestAnswers.routing("256772000002", false, null)))
                .answer(MapLcs.OP_PROVIDE_SUBSCRIBER_LOCATION,
                        (dialogue, invoke) -> result(MapLcs.OP_PROVIDE_SUBSCRIBER_LOCATION,
                                MapTestAnswers.provideLocation(10, MapTestAnswers.GSM_CGI)));

        assertThatThrownBy(() -> locator(link, new PlanCells()).locate(query(100)))
                .isInstanceOf(SignallingException.class)
                .satisfies(error -> assertThat(((SignallingException) error).code())
                        .isEqualTo("CELL_NOT_IN_PLAN"));
    }

    @Test
    void aPlanCellWithoutARadiusHasNoHonestUncertainty() {
        MapTestLink link = new MapTestLink()
                .answer(MapLcs.OP_SEND_ROUTING_INFO_FOR_LCS,
                        (dialogue, invoke) -> result(MapLcs.OP_SEND_ROUTING_INFO_FOR_LCS,
                                MapTestAnswers.routing("256772000002", false, null)))
                .answer(MapLcs.OP_PROVIDE_SUBSCRIBER_LOCATION,
                        (dialogue, invoke) -> result(MapLcs.OP_PROVIDE_SUBSCRIBER_LOCATION,
                                MapTestAnswers.provideLocation(10, MapTestAnswers.GSM_CGI)));
        PlanCells plan = new PlanCells().put("GSM", "4660", "1234",
                cell("GSM", "001-01-4660-1234", null, 80));

        assertThatThrownBy(() -> locator(link, plan).locate(query(100)))
                .isInstanceOf(SignallingException.class)
                .satisfies(error -> assertThat(((SignallingException) error).code())
                        .isEqualTo("CELL_WITHOUT_RADIUS"));
    }

    @Test
    void aHlrThatNamesNoServingNodeIsAFailure() {
        MapTestLink link = new MapTestLink()
                .answer(MapLcs.OP_SEND_ROUTING_INFO_FOR_LCS,
                        (dialogue, invoke) -> result(MapLcs.OP_SEND_ROUTING_INFO_FOR_LCS,
                                MapTestAnswers.routing(null, false, null)));

        assertThatThrownBy(() -> locator(link, new PlanCells()).locate(query(100)))
                .isInstanceOf(SignallingException.class)
                .satisfies(error -> assertThat(((SignallingException) error).code())
                        .isEqualTo("MAP_NO_SERVING_NODE"));
    }

    @Test
    void aMapErrorAtTheServingNodeKeepsItsName() {
        MapTestLink link = new MapTestLink()
                .answer(MapLcs.OP_SEND_ROUTING_INFO_FOR_LCS,
                        (dialogue, invoke) -> result(MapLcs.OP_SEND_ROUTING_INFO_FOR_LCS,
                                MapTestAnswers.routing("256772000002", false, null)))
                .answer(MapLcs.OP_PROVIDE_SUBSCRIBER_LOCATION,
                        (dialogue, invoke) -> new Tcap.ReturnError(1, 27, null));

        assertThatThrownBy(() -> locator(link, new PlanCells()).locate(query(100)))
                .isInstanceOf(SignallingException.class)
                .satisfies(error -> assertThat(((SignallingException) error).code())
                        .isEqualTo("ABSENT_SUBSCRIBER"));
    }

    @Test
    void theSameCellCodeUnderTwoRadioTechnologiesIsAmbiguous() {
        MapTestLink link = new MapTestLink()
                .answer(MapLcs.OP_SEND_ROUTING_INFO_FOR_LCS,
                        (dialogue, invoke) -> result(MapLcs.OP_SEND_ROUTING_INFO_FOR_LCS,
                                MapTestAnswers.routing("256772000002", false, null)))
                .answer(MapLcs.OP_PROVIDE_SUBSCRIBER_LOCATION,
                        (dialogue, invoke) -> result(MapLcs.OP_PROVIDE_SUBSCRIBER_LOCATION,
                                MapTestAnswers.provideLocation(10, MapTestAnswers.GSM_CGI)));
        PlanCells plan = new PlanCells()
                .put("GSM", "4660", "1234", cell("GSM", "001-01-4660-1234", 2500, 80))
                .put("UMTS", "4660", "1234", cell("UMTS", "001-01-4660-1234", 1500, 80));

        assertThatThrownBy(() -> locator(link, plan).locate(query(100)))
                .isInstanceOf(SignallingException.class)
                .satisfies(error -> assertThat(((SignallingException) error).code())
                        .isEqualTo("AMBIGUOUS_CELL"));
    }

    private static CellRepository.CellRow cell(String rat, String identity, Integer radius,
                                               Integer quality) {
        return new CellRepository.CellRow(1, rat, identity, "001", "01", "4660", "1234",
                "Kampala Central", new BigDecimal("0.3476"), new BigDecimal("32.5825"),
                new BigDecimal("18.0"), 120, 65, radius, null, 101, 1650, "MACRO",
                "drive-test", quality, LocalDate.now().minusMonths(2), Instant.now());
    }

    /**
     * Rows keyed by technology, area and cell. The ECGI lookup (no area) is a
     * separate key, so a test proves which lookup the locator chose.
     */
    private static final class PlanCells implements CellSelector {

        private final Map<String, CellRepository.CellRow> rows = new HashMap<>();
        final List<String> lookups = new ArrayList<>();

        PlanCells put(String rat, String area, String cell, CellRepository.CellRow row) {
            rows.put(rat + "/" + area + "/" + cell, row);
            return this;
        }

        @Override
        public Optional<CellRepository.CellRow> select(long tenantId, String rat, String mcc,
                                                       String mnc, String cellCode) {
            lookups.add("ecgi " + rat + "/" + cellCode);
            return Optional.ofNullable(rows.get(rat + "/*/" + cellCode));
        }

        @Override
        public Optional<CellRepository.CellRow> selectInArea(long tenantId, String rat, String mcc,
                                                             String mnc, String areaCode,
                                                             String cellCode) {
            lookups.add("area " + rat + "/" + areaCode + "/" + cellCode);
            return Optional.ofNullable(rows.get(rat + "/" + areaCode + "/" + cellCode));
        }
    }
}
