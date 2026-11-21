package com.hamiltel.gmlc.cell;

import java.util.Optional;

/**
 * Finds the one plan entry for a cell a network named. The ECGI carries no
 * TAC, so two rows for the same PLMN and cell code cannot be told apart: the
 * caller must not pick one.
 */
public interface CellSelector {

    Optional<CellRepository.CellRow> select(long tenantId, String rat, String mcc, String mnc,
                                            String cellCode);
}
