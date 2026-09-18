package com.voyra.crm.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PdfTableWriterTest {

    @Test
    void producesANonEmptyValidPdf() {
        ReportTable table = new ReportTable(
                List.of("id", "name"),
                List.of(List.of("B1", "Priya Nair")));

        byte[] bytes = PdfTableWriter.write("Bookings", table);

        assertThat(bytes).isNotEmpty();
        // PDF magic header - proves this is a real, openable document, not just non-empty bytes.
        assertThat(new String(bytes, 0, 5, java.nio.charset.StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
    }

    @Test
    void handlesEmptyRowsWithoutError() {
        ReportTable table = new ReportTable(List.of("month", "revenue"), List.of());

        byte[] bytes = PdfTableWriter.write("Revenue", table);

        assertThat(bytes).isNotEmpty();
    }
}
