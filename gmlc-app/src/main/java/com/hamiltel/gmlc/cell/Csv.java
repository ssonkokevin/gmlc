package com.hamiltel.gmlc.cell;

import java.util.ArrayList;
import java.util.List;

/**
 * A small RFC 4180 reader: quoted fields, doubled quotes, CRLF or LF, and
 * quoted newlines. Each record keeps the line it started on so an import
 * report can point at the file, not at the parsed array.
 */
public final class Csv {

    public record Row(int line, List<String> values) {
    }

    private Csv() {
    }

    public static List<Row> parse(String text) {
        String input = text.startsWith("\uFEFF") ? text.substring(1) : text;
        List<Row> rows = new ArrayList<>();

        List<String> values = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        int line = 1;
        int recordLine = 1;
        boolean fieldStarted = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < input.length() && input.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    if (c == '\n') {
                        line++;
                    }
                    field.append(c);
                }
                continue;
            }

            switch (c) {
                case '"' -> {
                    if (!fieldStarted && field.isEmpty()) {
                        quoted = true;
                        fieldStarted = true;
                    } else {
                        field.append(c);
                    }
                }
                case ',' -> {
                    values.add(field.toString());
                    field.setLength(0);
                    fieldStarted = false;
                }
                case '\r' -> {
                    // Consumed by the following \n; a lone CR ends the record the same way.
                    if (i + 1 >= input.length() || input.charAt(i + 1) != '\n') {
                        values.add(field.toString());
                        rows.add(new Row(recordLine, List.copyOf(values)));
                        values.clear();
                        field.setLength(0);
                        fieldStarted = false;
                        line++;
                        recordLine = line;
                    }
                }
                case '\n' -> {
                    values.add(field.toString());
                    rows.add(new Row(recordLine, List.copyOf(values)));
                    values.clear();
                    field.setLength(0);
                    fieldStarted = false;
                    line++;
                    recordLine = line;
                }
                default -> {
                    field.append(c);
                    fieldStarted = true;
                }
            }
        }

        if (!values.isEmpty() || !field.isEmpty() || fieldStarted) {
            values.add(field.toString());
            rows.add(new Row(recordLine, List.copyOf(values)));
        }
        return rows;
    }
}
