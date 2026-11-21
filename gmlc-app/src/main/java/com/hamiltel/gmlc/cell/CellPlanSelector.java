package com.hamiltel.gmlc.cell;

import com.hamiltel.gmlc.diameter.SignallingException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Selects a cell by PLMN and cell code only, because that is all an ECGI
 * gives. A plan with two such rows is a plan defect, and answering with either
 * one would put a subscriber in the wrong place.
 */
@Component
public class CellPlanSelector implements CellSelector {

    private final CellRepository cells;

    public CellPlanSelector(CellRepository cells) {
        this.cells = cells;
    }

    @Override
    public Optional<CellRepository.CellRow> select(long tenantId, String rat, String mcc, String mnc,
                                                   String cellCode) {
        List<CellRepository.CellRow> rows = cells.findByPlmnAndCellCode(tenantId, rat, mcc, mnc, cellCode);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        if (rows.size() > 1) {
            throw new SignallingException("AMBIGUOUS_CELL",
                    rows.size() + " cells in the plan share " + mcc + "-" + mnc + "/" + cellCode);
        }
        return Optional.of(rows.get(0));
    }
}
