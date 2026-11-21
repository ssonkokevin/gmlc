package com.hamiltel.gmlc.ops;

/**
 * Console identifiers are masked by default: the first five and last two
 * characters survive, the middle becomes asterisks. Revealing is a permission
 * and writes an audit row; this helper is the only path a table should use.
 */
public final class OpsMasking {

    private static final int HEAD = 5;
    private static final int TAIL = 2;

    private OpsMasking() {
    }

    public static String mask(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        String trimmed = value.trim();
        if (trimmed.length() <= HEAD + TAIL + 1) {
            return "*".repeat(trimmed.length());
        }
        return trimmed.substring(0, HEAD)
                + "*".repeat(trimmed.length() - HEAD - TAIL)
                + trimmed.substring(trimmed.length() - TAIL);
    }
}
