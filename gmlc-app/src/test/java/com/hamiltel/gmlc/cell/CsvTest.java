package com.hamiltel.gmlc.cell;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CsvTest {

    @Test
    void quotedCommasAndDoubledQuotesSurvive() {
        List<Csv.Row> rows = Csv.parse("a,b\r\n\"x,y\",\"he said \"\"hi\"\"\"\r\n");

        assertThat(rows).hasSize(2);
        assertThat(rows.get(1).values()).containsExactly("x,y", "he said \"hi\"");
        assertThat(rows.get(1).line()).isEqualTo(2);
    }

    @Test
    void aQuotedNewlineStaysInTheFieldAndAdvancesTheLineNumber() {
        List<Csv.Row> rows = Csv.parse("\"line1\nline2\",z\nnext,row\n");

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).values()).containsExactly("line1\nline2", "z");
        assertThat(rows.get(1).line()).isEqualTo(3);
    }

    @Test
    void aByteOrderMarkIsIgnored() {
        List<Csv.Row> rows = Csv.parse("\uFEFFrat,plmn_mcc\nLTE,001\n");

        assertThat(rows.get(0).values()).containsExactly("rat", "plmn_mcc");
    }

    @Test
    void aTrailingNewlineDoesNotCreateAnEmptyRecord() {
        assertThat(Csv.parse("a,b\n")).hasSize(1);
        assertThat(Csv.parse("a,b")).hasSize(1);
    }
}
