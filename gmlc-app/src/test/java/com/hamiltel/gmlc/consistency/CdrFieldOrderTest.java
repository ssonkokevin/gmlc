package com.hamiltel.gmlc.consistency;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The CDR export is an operator-visible contract: a downstream spreadsheet or
 * billing import keys on the column order. {@code fixtures/cdr-fields.txt} is
 * the committed order; this test reads the header literals and the row
 * accessors out of {@code OpsMonitoringController.exportExchanges} and compares
 * both sequences with the fixture.
 *
 * <p>Reordering only the header or only the row call keeps the export
 * compiling, which is exactly the mistake this test exists to catch.
 */
class CdrFieldOrderTest {

    private static final Pattern COLUMN = Pattern.compile("\"([a-z0-9_]+)\"");
    private static final Pattern ACCESSOR = Pattern.compile("row\\.(\\w+)\\(\\)");

    @Test
    void exchangeExportMatchesTheCommittedFieldOrder() throws IOException {
        List<String[]> expected = fixture();
        List<String> source = Files.readAllLines(
                RepoFiles.find("gmlc-app/src/main/java/com/hamiltel/gmlc/ops/OpsMonitoringController.java"),
                StandardCharsets.UTF_8);
        String text = String.join("\n", source);

        List<String> columns = headerColumns(text);
        List<String> accessors = rowAccessors(text);

        assertThat(columns)
                .as("the CSV header in OpsMonitoringController must match fixtures/cdr-fields.txt")
                .containsExactlyElementsOf(expected.stream().map(pair -> pair[0]).toList());
        assertThat(accessors)
                .as("the exported row accessors must match fixtures/cdr-fields.txt")
                .containsExactlyElementsOf(expected.stream().map(pair -> pair[1]).toList());
        assertThat(columns).doesNotHaveDuplicates();
        assertThat(accessors).doesNotHaveDuplicates();
    }

    private static List<String[]> fixture() throws IOException {
        List<String[]> fields = new ArrayList<>();
        for (String line : Files.readAllLines(RepoFiles.find("fixtures/cdr-fields.txt"),
                StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            String[] pair = trimmed.split("=", 2);
            assertThat(pair).as("fixture line '%s' must be column=accessor", trimmed)
                    .hasSize(2);
            assertThat(pair[0]).as("column name in '%s'", trimmed).isNotBlank();
            assertThat(pair[1]).as("accessor in '%s'", trimmed).isNotBlank();
            fields.add(pair);
        }
        assertThat(fields).as("fixtures/cdr-fields.txt must list the export columns")
                .isNotEmpty();
        return fields;
    }

    private static List<String> headerColumns(String text) {
        int start = text.indexOf("out.println(csv(\"cdr_id\"");
        assertThat(start).as("export header starting with cdr_id").isGreaterThanOrEqualTo(0);
        int end = text.indexOf("));", start);
        assertThat(end).as("export header terminator").isGreaterThan(start);
        String block = text.substring(start, end);
        List<String> columns = new ArrayList<>();
        Matcher matcher = COLUMN.matcher(block);
        while (matcher.find()) {
            columns.add(matcher.group(1));
        }
        return columns;
    }

    private static List<String> rowAccessors(String text) {
        int start = text.indexOf("out.println(csv(row.cdrId()");
        assertThat(start).as("export row starting with row.cdrId()").isGreaterThanOrEqualTo(0);
        int end = text.indexOf("));", start);
        assertThat(end).as("export row terminator").isGreaterThan(start);
        String block = text.substring(start, end);
        List<String> accessors = new ArrayList<>();
        Matcher matcher = ACCESSOR.matcher(block);
        while (matcher.find()) {
            accessors.add(matcher.group(1));
        }
        return accessors;
    }
}
