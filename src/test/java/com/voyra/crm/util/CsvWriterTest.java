package com.voyra.crm.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CsvWriterTest {

    @Test
    void headerAndRowsProduceExpectedLineCount() {
        String csv = CsvWriter.write(List.of("id", "name"), List.of(List.of("1", "Jane"), List.of("2", "John")));
        assertThat(csv.split("\n")).hasSize(3);
    }

    @Test
    void valueContainingCommaIsQuoted() {
        String csv = CsvWriter.write(List.of("destination"), List.of(List.of("Dubai, UAE")));
        assertThat(csv).contains("\"Dubai, UAE\"");
    }

    @Test
    void valueContainingDoubleQuoteIsEscaped() {
        String csv = CsvWriter.write(List.of("note"), List.of(List.of("She said \"hello\"")));
        assertThat(csv).contains("\"She said \"\"hello\"\"\"");
    }
}
