package com.voyra.crm.util;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class XlsxWriterTest {

    @Test
    void writesHeaderAndRowsToOneSheet() throws IOException {
        ReportTable table = new ReportTable(
                List.of("id", "name"),
                List.of(List.of("B1", "Priya Nair"), List.of("B2", "Arjun Mehta")));

        byte[] bytes = XlsxWriter.write("Bookings", table);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = workbook.getSheet("Bookings");
            assertThat(sheet).isNotNull();
            Row header = sheet.getRow(0);
            assertThat(header.getCell(0).getStringCellValue()).isEqualTo("id");
            assertThat(header.getCell(1).getStringCellValue()).isEqualTo("name");
            assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("Priya Nair");
            assertThat(sheet.getRow(2).getCell(1).getStringCellValue()).isEqualTo("Arjun Mehta");
            assertThat(sheet.getLastRowNum()).isEqualTo(2);
        }
    }

    @Test
    void handlesEmptyRowsWithoutError() throws IOException {
        ReportTable table = new ReportTable(List.of("month", "revenue"), List.of());

        byte[] bytes = XlsxWriter.write("Revenue", table);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = workbook.getSheet("Revenue");
            assertThat(sheet.getLastRowNum()).isEqualTo(0);
        }
    }
}
