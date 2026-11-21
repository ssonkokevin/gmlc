package com.hamiltel.gmlc.cell;

import java.util.Map;

/**
 * Field-level refusals for one cell. The map is ordered and names the field
 * the caller must fix, so a CSV row and a JSON body report alike.
 */
public class InvalidCellException extends RuntimeException {

    private final Map<String, String> fields;

    public InvalidCellException(Map<String, String> fields) {
        super("one or more cell fields are invalid");
        this.fields = fields;
    }

    public Map<String, String> fields() {
        return fields;
    }
}
