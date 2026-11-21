package com.hamiltel.gmlc.location;

import com.hamiltel.gmlc.cell.CellRepository;
import com.hamiltel.gmlc.cell.CellSelector;
import com.hamiltel.gmlc.diameter.SignallingException;
import com.hamiltel.gmlc.map.BerException;
import com.hamiltel.gmlc.map.MapClient;
import com.hamiltel.gmlc.map.MapException;
import com.hamiltel.gmlc.map.MapLcs;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Cell-ID positioning over the MAP face: the HLR routes to the serving node,
 * the node names the cell, and the operator's plan turns that cell into a
 * bounded circle. An MME is not a MAP PSL peer, so an EPS subscriber is asked
 * about with ATI, which the HLR answers from the MME.
 *
 * <p>The answer is only as good as the plan entry. A cell with no radius has
 * no honest uncertainty, and a cell absent from the plan has no position at
 * all; a MAP error keeps its TS 29.002 name, and a circle without a cell is
 * reported as no source rather than stored under an engine that did not
 * produce it.
 */
public class MapCellLocator implements LocationLocator {

    /** Neutral confidence for a plan row nobody has qualified. */
    private static final int UNQUALIFIED_CONFIDENCE = 50;

    private static final int NODE_COLUMN = 64;

    /** A CS or PS cell code does not say GSM from UMTS; the plan row does. */
    private static final List<String> CS_RATS = List.of("GSM", "UMTS");
    private static final List<String> PS_RATS = List.of("GSM", "UMTS");
    private static final List<String> EPS_RATS = List.of("LTE");

    private final MapClient map;
    private final CellSelector cells;

    public MapCellLocator(MapClient map, CellSelector cells) {
        this.map = map;
        this.cells = cells;
    }

    @Override
    public Optional<LocationEstimate> locate(Query query) {
        try {
            return resolve(query);
        } catch (MapException e) {
            throw new SignallingException(e.code(), e.getMessage(), e);
        } catch (BerException e) {
            throw new SignallingException("MAP_DECODE",
                    "the MAP answer is malformed: " + e.getMessage(), e);
        }
    }

    private Optional<LocationEstimate> resolve(Query query) {
        MapLcs.RoutingInfo routing = map.routeInfo(query.imsi(), query.msisdn());
        if (routing.mmeName() != null) {
            return anyTime(query, routing);
        }
        if (routing.networkNode() == null) {
            throw new MapException("MAP_NO_SERVING_NODE",
                    "the HLR named no MSC, SGSN or MME for the subscriber");
        }
        return provideLocation(query, routing);
    }

    private Optional<LocationEstimate> provideLocation(Query query, MapLcs.RoutingInfo routing) {
        MapLcs.ProvideSubscriberLocationResult answer = map.provideLocation(
                routing.networkNode(), routing.gprsNode(), query.imsi(), query.msisdn(),
                query.accuracyMeters(), query.lcsClientName());
        MapLcs.CellArea cell = answer.cell();
        if (cell == null || cell.cellCode() == null) {
            return Optional.empty();
        }
        CellRepository.CellRow row = planCell(query.tenantId(),
                routing.gprsNode() ? PS_RATS : CS_RATS, cell);
        return Optional.of(estimate(query, row, ageSeconds(answer.ageMinutes()),
                answer.accuracyFulfilled(), routing.networkNode(),
                List.of("MAP_SRILCS", "MAP_PSL")));
    }

    private Optional<LocationEstimate> anyTime(Query query, MapLcs.RoutingInfo routing) {
        MapLcs.AnyTimeResult answer = map.anyTime(query.imsi(), query.msisdn());
        if (answer.eps() != null && answer.eps().cell() != null) {
            CellRepository.CellRow row =
                    planCell(query.tenantId(), EPS_RATS, answer.eps().cell());
            return Optional.of(estimate(query, row, ageSeconds(answer.eps().ageMinutes()), null,
                    routing.mmeName(), List.of("MAP_SRILCS", "MAP_ATI")));
        }
        if (answer.ps() != null && hasCell(answer.ps().cell())) {
            CellRepository.CellRow row =
                    planCell(query.tenantId(), PS_RATS, answer.ps().cell());
            return Optional.of(estimate(query, row, ageSeconds(answer.ps().ageMinutes()), null,
                    nodeOr(answer.ps().sgsnNumber(), routing.networkNode()),
                    List.of("MAP_SRILCS", "MAP_ATI")));
        }
        if (answer.cs() != null && hasCell(answer.cs().cell())) {
            CellRepository.CellRow row =
                    planCell(query.tenantId(), CS_RATS, answer.cs().cell());
            return Optional.of(estimate(query, row, ageSeconds(answer.cs().ageMinutes()), null,
                    nodeOr(answer.cs().vlrNumber(), routing.networkNode()),
                    List.of("MAP_SRILCS", "MAP_ATI")));
        }
        return Optional.empty();
    }

    private static boolean hasCell(MapLcs.CellArea cell) {
        return cell != null && cell.cellCode() != null;
    }

    private CellRepository.CellRow planCell(long tenantId, List<String> rats, MapLcs.CellArea cell) {
        List<CellRepository.CellRow> matches = new ArrayList<>(1);
        String area = Integer.toString(cell.areaCode());
        String code = Integer.toString(cell.cellCode());
        for (String rat : rats) {
            cells.selectInArea(tenantId, rat, cell.mcc(), cell.mnc(), area, code)
                    .ifPresent(matches::add);
        }
        return one(matches, cell.mcc() + "-" + cell.mnc() + "/" + cell.cellCode());
    }

    private CellRepository.CellRow planCell(long tenantId, List<String> rats, MapLcs.EutranCell cell) {
        List<CellRepository.CellRow> matches = new ArrayList<>(1);
        String code = Long.toString(cell.cellCode());
        for (String rat : rats) {
            Optional<CellRepository.CellRow> row = cell.tac() == null
                    ? cells.select(tenantId, rat, cell.mcc(), cell.mnc(), code)
                    : cells.selectInArea(tenantId, rat, cell.mcc(), cell.mnc(),
                            Integer.toString(cell.tac()), code);
            row.ifPresent(matches::add);
        }
        return one(matches, cell.mcc() + "-" + cell.mnc() + "/" + cell.cellCode());
    }

    private static CellRepository.CellRow one(List<CellRepository.CellRow> matches, String cell) {
        if (matches.isEmpty()) {
            throw new SignallingException("CELL_NOT_IN_PLAN",
                    "serving cell " + cell + " is not in the cell plan");
        }
        if (matches.size() > 1) {
            throw new SignallingException("AMBIGUOUS_CELL",
                    matches.size() + " plan rows share " + cell
                            + " across radio technologies");
        }
        return matches.get(0);
    }

    private static LocationEstimate estimate(Query query, CellRepository.CellRow row,
                                             Integer ageSeconds, Boolean answerFulfilled,
                                             String node, List<String> methods) {
        if (row.radiusM() == null) {
            throw new SignallingException("CELL_WITHOUT_RADIUS",
                    "cell " + row.cellIdentity() + " has no radius, so no honest uncertainty");
        }
        int uncertainty = row.radiusM();
        boolean fitsRequest = query.accuracyMeters() == null || uncertainty <= query.accuracyMeters();
        boolean fulfilled = fitsRequest && (answerFulfilled == null || answerFulfilled);
        String positioningNode = node == null
                ? ""
                : node.substring(0, Math.min(NODE_COLUMN, node.length()));
        return new LocationEstimate(
                row.rat(),
                "CELL_ID",
                "CIRCLE",
                row.latitude(),
                row.longitude(),
                uncertainty,
                row.dataQuality() == null ? UNQUALIFIED_CONFIDENCE : row.dataQuality(),
                row.dataQuality(),
                ageSeconds == null ? 0 : ageSeconds,
                fulfilled,
                query.accuracyMeters(),
                row.cellIdentity(),
                "CELL_DB",
                positioningNode,
                Instant.now(),
                methods);
    }

    private static Integer ageSeconds(Integer minutes) {
        if (minutes == null) {
            return null;
        }
        if (minutes < 0) {
            throw new MapException("MAP_AGE", "the answer carries a negative location age");
        }
        return (int) Math.min((long) minutes * 60, Integer.MAX_VALUE);
    }

    private static String nodeOr(String preferred, String fallback) {
        return preferred != null ? preferred : fallback;
    }
}
