package com.hamiltel.gmlc.cell;

/** No cell with that RAT and canonical identity for this tenant. */
public class CellNotFoundException extends RuntimeException {

    private final String rat;
    private final String cellIdentity;

    public CellNotFoundException(String rat, String cellIdentity) {
        super("no such cell");
        this.rat = rat;
        this.cellIdentity = cellIdentity;
    }

    public String rat() {
        return rat;
    }

    public String cellIdentity() {
        return cellIdentity;
    }
}
