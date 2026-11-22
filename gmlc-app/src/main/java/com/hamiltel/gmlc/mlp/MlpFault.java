package com.hamiltel.gmlc.mlp;

/**
 * A request the adapter will not process. The OMA result code travels with the
 * fault so the answer can refuse with the reason the standard names rather
 * than a generic error.
 */
public class MlpFault extends RuntimeException {

    private final int resid;
    private final String addInfo;

    public MlpFault(int resid, String message, String addInfo) {
        super(message);
        this.resid = resid;
        this.addInfo = addInfo;
    }

    public int resid() {
        return resid;
    }

    public String addInfo() {
        return addInfo;
    }
}
