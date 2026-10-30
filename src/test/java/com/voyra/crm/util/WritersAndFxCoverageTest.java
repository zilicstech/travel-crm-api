package com.voyra.crm.util;

import com.voyra.crm.dto.BookingDocumentCreateRequest;
import com.voyra.crm.enums.MarkupMode;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The edges the existing CSV, XLSX, search-path and FX tests leave open. */
class WritersAndFxCoverageTest {

    @Test
    void csvWritesNullAsEmptyAndQuotesANewline() {
        String csv = CsvWriter.write(List.of("a", "b"), List.of(Arrays.asList(null, "line1\nline2")));

        assertThat(csv).isEqualTo("a,b\n,\"line1\nline2\"\n");
    }

    @Test
    void xlsxWritesNullCellsAsEmpty() throws Exception {
        byte[] bytes = XlsxWriter.write("S", new ReportTable(List.of("h1", "h2"), List.of(Arrays.asList(null, "v"))));

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(wb.getSheet("S").getRow(1).getCell(0).getStringCellValue()).isEmpty();
            assertThat(wb.getSheet("S").getRow(1).getCell(1).getStringCellValue()).isEqualTo("v");
        }
    }

    @Test
    void theSearchPathIsSetOnTheConnectionAndTheStatementClosed() throws Exception {
        Connection conn = mock(Connection.class);
        Statement stmt = mock(Statement.class);
        when(conn.createStatement()).thenReturn(stmt);

        TenantSearchPathUtil.applyOnConnection(conn, "AB-12");

        verify(stmt).execute("SET search_path TO tenant_ab12, public");
        verify(stmt).close();
    }

    @Test
    void aConvertedAmountKnowsWhetherItIsForeign() {
        FxMath.Converted inr = FxMath.toBase(" ", null, null, "INR");

        assertThat(inr.currency()).isEqualTo("INR");
        assertThat(inr.amount()).isEqualByComparingTo("0");
        assertThat(inr.base()).isEqualByComparingTo("0.00");
        assertThat(inr.foreign("INR")).isFalse();
        assertThat(FxMath.toBase("usd", BigDecimal.ONE, new BigDecimal("80"), "INR").foreign("INR")).isTrue();
    }

    @Test
    void aZeroOrNegativeRateIsRefused() {
        assertThatThrownBy(() -> FxMath.toBase("USD", BigDecimal.ONE, BigDecimal.ZERO, "INR"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Enter the USD rate for this date (1 USD in INR)");
    }

    @Test
    void aFlatMarkupWithoutAnInputKeepsTheBaseMarkupValue() {
        BookingDocumentCreateRequest r = new BookingDocumentCreateRequest();
        r.setNetCost(new BigDecimal("1000"));
        r.setMarkupMode(MarkupMode.FLAT);
        r.setMarkupValue(new BigDecimal("50"));

        FxMath.Pricing p = FxMath.resolve(r, "INR");

        assertThat(p.netCost()).isEqualByComparingTo("1000");
        assertThat(p.markupValue()).isEqualByComparingTo("50");
        assertThat(p.purchase()).isNull();
        assertThat(p.markup()).isNull();
    }
}
