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

    /**
     * The same lookup when the network also named the area (an LAC from MAP, a
     * TAC from an EPS answer). An implementation that can use the area should
     * do so; the default keeps the ECGI contract and ignores it.
     */
    default Optional<CellRepository.CellRow> selectInArea(long tenantId, String rat, String mcc,
                                                          String mnc, String areaCode,
                                                          String cellCode) {
        return select(tenantId, rat, mcc, mnc, cellCode);
    }
}
