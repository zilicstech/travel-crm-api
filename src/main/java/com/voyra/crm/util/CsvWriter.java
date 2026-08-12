package com.voyra.crm.util;

import java.util.List;

/** Minimal CSV writer for report exports - escapes quotes/commas, no external dependency needed for this shape of data. */
public final class CsvWriter {

    private CsvWriter() {
    }

    public static String write(List<String> header, List<List<String>> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append(toLine(header));
        for (List<String> row : rows) {
            sb.append(toLine(row));
        }
        return sb.toString();
    }

    private static String toLine(List<String> fields) {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                line.append(',');
            }
            line.append(escape(fields.get(i)));
        }
        line.append('\n');
        return line.toString();
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
