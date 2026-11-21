package com.hamiltel.gmlc.location;

import com.hamiltel.gmlc.cell.CellRepository;
import com.hamiltel.gmlc.cell.CellSelector;
import com.hamiltel.gmlc.diameter.ServingCell;
import com.hamiltel.gmlc.diameter.ServingNode;
import com.hamiltel.gmlc.diameter.SignallingException;
import com.hamiltel.gmlc.diameter.SlgClient;
import com.hamiltel.gmlc.diameter.SlhClient;
import com.hamiltel.gmlc.diameter.SubscriberId;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Cell-ID positioning: the HSS names the serving MME, the MME names the
 * serving cell, and the operator's plan turns that cell into a bounded circle.
 *
 * <p>The answer is only as good as the plan entry. A cell with no radius has
 * no honest uncertainty, and a cell that is absent from the plan has no
 * position at all; both are reported as failures rather than approximated.
 *
 * <p>Instances are built by {@code LocationConfiguration}, which chooses this
 * locator or the MAP one for the deployment.
 */
public class DiameterCellLocator implements LocationLocator {

    /** Neutral confidence for a plan row nobody has qualified. */
    private static final int UNQUALIFIED_CONFIDENCE = 50;

    private static final int NODE_COLUMN = 64;

    private final SlhClient slh;
    private final SlgClient slg;
    private final CellSelector cells;

    public DiameterCellLocator(SlhClient slh, SlgClient slg, CellSelector cells) {
        this.slh = slh;
        this.slg = slg;
        this.cells = cells;
    }

    @Override
    public Optional<LocationEstimate> locate(Query query) {
        SubscriberId subscriber = new SubscriberId(query.imsi(), query.msisdn());
        ServingNode node = slh.routeInfo(subscriber)
                .orElseThrow(() -> new SignallingException("NO_SERVING_NODE",
                        "the HSS answered without a serving node this gateway can address"));

        int accuracy = query.accuracyMeters() == null ? 0 : query.accuracyMeters();
        Optional<ServingCell> serving = slg.provideLocation(node, subscriber, accuracy,
                query.lcsClientName(), query.emergency());
        if (serving.isEmpty()) {
            return Optional.empty();
        }
        ServingCell cell = serving.get();

        CellRepository.CellRow row = cells.select(query.tenantId(), cell.rat(), cell.mcc(),
                        cell.mnc(), cell.cellCode())
                .orElseThrow(() -> new SignallingException("CELL_NOT_IN_PLAN",
                        "serving cell " + cell.mcc() + "-" + cell.mnc() + "/" + cell.cellCode()
                                + " is not in the cell plan"));
        if (row.radiusM() == null) {
            throw new SignallingException("CELL_WITHOUT_RADIUS",
                    "cell " + row.cellIdentity() + " has no radius, so no honest uncertainty");
        }

        int uncertainty = row.radiusM();
        boolean fitsRequest = query.accuracyMeters() == null || uncertainty <= query.accuracyMeters();
        boolean fulfilled = fitsRequest
                && (cell.accuracyFulfilled() == null || cell.accuracyFulfilled());

        return Optional.of(new LocationEstimate(
                cell.rat(),
                "CELL_ID",
                "CIRCLE",
                row.latitude(),
                row.longitude(),
                uncertainty,
                row.dataQuality() == null ? UNQUALIFIED_CONFIDENCE : row.dataQuality(),
                row.dataQuality(),
                cell.ageSeconds() == null ? 0 : cell.ageSeconds(),
                fulfilled,
                query.accuracyMeters(),
                row.cellIdentity(),
                "CELL_DB",
                node.diameterName().substring(0, Math.min(NODE_COLUMN, node.diameterName().length())),
                Instant.now(),
                List.of("SLH_RIR", "SLG_PLA")));
    }
}
