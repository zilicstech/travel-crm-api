package com.voyra.crm.util;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/** Same {@link ReportTable} every export starts from, written as a single-sheet .xlsx. */
public final class XlsxWriter {

    private XlsxWriter() {
    }

    public static byte[] write(String sheetName, ReportTable table) {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet(sheetName);

            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);

            Row headerRow = sheet.createRow(0);
            List<String> header = table.header();
            for (int c = 0; c < header.size(); c++) {
                Cell cell = headerRow.createCell(c);
                cell.setCellValue(header.get(c));
                cell.setCellStyle(headerStyle);
            }

            List<List<String>> rows = table.rows();
            for (int r = 0; r < rows.size(); r++) {
                Row row = sheet.createRow(r + 1);
                List<String> values = rows.get(r);
                for (int c = 0; c < values.size(); c++) {
                    row.createCell(c).setCellValue(values.get(c) != null ? values.get(c) : "");
                }
            }

            for (int c = 0; c < header.size(); c++) {
                sheet.autoSizeColumn(c);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write .xlsx report", e);
        }
    }
}
