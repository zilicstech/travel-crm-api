package com.voyra.crm.util;

import java.util.List;

/**
 * A report's data, computed once and fed into whichever writer the caller wants
 * (CsvWriter, XlsxWriter, PdfTableWriter) - every cell is already a display string, same
 * convention CsvWriter used before this existed.
 */
public record ReportTable(List<String> header, List<List<String>> rows) {
}
