package com.voyra.crm.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyra.crm.entity.BankStatementProfile;
import com.voyra.crm.enums.BankAmountConvention;
import com.voyra.crm.enums.BankTransactionDirection;
import com.voyra.crm.models.ParsedBankLine;
import com.voyra.crm.models.StatementParseResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Rule 4.2.1 - the CSV implementation of {@link StatementParser}. Pure: takes bytes and a
 * profile, returns parsed lines plus a row-level error list, nothing else. Handles RFC4180-style
 * quoted fields without a third-party dependency, since bank statement CSVs are simple enough
 * that pulling in commons-csv for this one need is not worth a new dependency.
 */
@Component
@RequiredArgsConstructor
public class CsvStatementParser implements StatementParser {

    private final ObjectMapper objectMapper;

    @Override
    public String supportedFormat() {
        return "CSV";
    }

    /** Excel's "CSV UTF-8" export starts with a byte-order mark that would otherwise glue itself to the first header name. */
    static String stripBom(String text) {
        return !text.isEmpty() && text.charAt(0) == '\uFEFF' ? text.substring(1) : text;
    }

    @Override
    public StatementParseResult parse(byte[] fileContent, BankStatementProfile profile) {
        Map<String, String> columnMap = readColumnMap(profile.getColumnMap());
        DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern(profile.getDateFormat());

        List<ParsedBankLine> lines = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(
                new StringReader(stripBom(new String(fileContent, StandardCharsets.UTF_8))))) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                return new StatementParseResult(lines, List.of("File is empty"));
            }
            List<String> headers = splitCsvLine(headerLine);

            String row;
            int rowNumber = 1;
            while ((row = reader.readLine()) != null) {
                rowNumber++;
                if (row.isBlank()) {
                    continue;
                }
                try {
                    lines.add(parseRow(headers, splitCsvLine(row), columnMap, dateFormatter, profile.getAmountConvention()));
                } catch (Exception e) {
                    errors.add("Row " + rowNumber + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        return new StatementParseResult(lines, errors);
    }

    private ParsedBankLine parseRow(List<String> headers, List<String> values, Map<String, String> columnMap,
                                     DateTimeFormatter dateFormatter, BankAmountConvention convention) {
        Map<String, String> row = new java.util.HashMap<>();
        for (int i = 0; i < headers.size() && i < values.size(); i++) {
            row.put(headers.get(i).trim(), values.get(i).trim());
        }

        String dateColumn = requireMapping(columnMap, "date");
        String descriptionColumn = columnMap.get("description");
        String referenceColumn = columnMap.get("reference");
        String balanceColumn = columnMap.get("balance");

        String dateRaw = requireValue(row, dateColumn, "date");
        LocalDate txnDate = LocalDate.parse(dateRaw, dateFormatter);

        BigDecimal amount;
        BankTransactionDirection direction;
        if (convention == BankAmountConvention.DEBIT_CREDIT_COLUMNS) {
            String debitColumn = requireMapping(columnMap, "debit");
            String creditColumn = requireMapping(columnMap, "credit");
            BigDecimal debit = parseAmountOrZero(row.get(debitColumn));
            BigDecimal credit = parseAmountOrZero(row.get(creditColumn));
            if (debit.compareTo(BigDecimal.ZERO) > 0 && credit.compareTo(BigDecimal.ZERO) > 0) {
                throw new IllegalArgumentException("row carries both a debit and a credit amount");
            }
            if (debit.compareTo(BigDecimal.ZERO) == 0 && credit.compareTo(BigDecimal.ZERO) == 0) {
                throw new IllegalArgumentException("row carries neither a debit nor a credit amount");
            }
            direction = credit.compareTo(BigDecimal.ZERO) > 0 ? BankTransactionDirection.CREDIT : BankTransactionDirection.DEBIT;
            amount = credit.compareTo(BigDecimal.ZERO) > 0 ? credit : debit;
        } else {
            String amountColumn = requireMapping(columnMap, "amount");
            String signedRaw = requireValue(row, amountColumn, "amount");
            BigDecimal signed = parseAmount(signedRaw);
            boolean positiveIsCredit = !"false".equalsIgnoreCase(columnMap.get("positiveIsCredit"));
            boolean isCredit = positiveIsCredit ? signed.signum() >= 0 : signed.signum() < 0;
            direction = isCredit ? BankTransactionDirection.CREDIT : BankTransactionDirection.DEBIT;
            amount = signed.abs();
        }

        return new ParsedBankLine(
                txnDate,
                txnDate,
                descriptionColumn != null ? row.get(descriptionColumn) : null,
                referenceColumn != null ? row.get(referenceColumn) : null,
                amount,
                direction,
                balanceColumn != null ? parseAmountOrZero(row.get(balanceColumn)) : null);
    }

    private static BigDecimal parseAmount(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("amount is blank");
        }
        return toDecimal(raw);
    }

    /** Strips thousands separators and a leading currency marker (₹, Rs, INR, $) before parsing. */
    static BigDecimal toDecimal(String raw) {
        String cleaned = raw.replaceAll("(?i)(₹|rs\\.?|inr|\\$)", "").replace(",", "").trim();
        try {
            return new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("amount \"" + raw.trim() + "\" is not a valid number");
        }
    }

    private static BigDecimal parseAmountOrZero(String raw) {
        if (raw == null || raw.isBlank()) {
            return BigDecimal.ZERO;
        }
        return toDecimal(raw);
    }

    private static String requireMapping(Map<String, String> columnMap, String logicalField) {
        String column = columnMap.get(logicalField);
        if (column == null || column.isBlank()) {
            throw new IllegalStateException("Statement profile's column_map has no mapping for '" + logicalField + "'");
        }
        return column;
    }

    private static String requireValue(Map<String, String> row, String column, String logicalField) {
        String value = row.get(column);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing value for '" + logicalField + "' (column '" + column + "')");
        }
        return value;
    }

    private Map<String, String> readColumnMap(String columnMapJson) {
        try {
            return objectMapper.readValue(columnMapJson, Map.class);
        } catch (IOException e) {
            throw new IllegalStateException("Statement profile's column_map is not valid JSON: " + e.getMessage(), e);
        }
    }

    /** One CSV record and the physical line it starts on (1-based). */
    record CsvRecord(int line, List<String> fields) {
    }

    /**
     * RFC 4180 records over a whole text: quoted fields may hold commas, doubled quotes and line breaks;
     * a record ends at \n, \r\n or a bare \r (old Mac/Excel exports). An unterminated quote is refused.
     */
    static List<CsvRecord> records(String text) {
        List<CsvRecord> out = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        int line = 1;
        int start = 1;
        int n = text.length();
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            boolean crlf = c == '\r' && i + 1 < n && text.charAt(i + 1) == '\n';
            if (inQuotes) {
                if (c == '"' && i + 1 < n && text.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else if (c == '"') {
                    inQuotes = false;
                } else {
                    if (c == '\n' || (c == '\r' && !crlf)) {
                        line++;
                    }
                    current.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                fields.add(current.toString());
                current.setLength(0);
            } else if (c == '\r' || c == '\n') {
                if (crlf) {
                    i++;
                }
                fields.add(current.toString());
                current.setLength(0);
                out.add(new CsvRecord(start, fields));
                fields = new ArrayList<>();
                start = ++line;
            } else {
                current.append(c);
            }
        }
        if (inQuotes) {
            throw new IllegalArgumentException("Line " + start + ": a quoted field is never closed");
        }
        if (!fields.isEmpty() || current.length() > 0) {
            fields.add(current.toString());
            out.add(new CsvRecord(start, fields));
        }
        return out;
    }

    /** Minimal RFC4180 line splitter - handles quoted fields with embedded commas and doubled-quote escaping. */
    private static List<String> splitCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    current.append(c);
                }
            } else {
                if (c == '"') {
                    inQuotes = true;
                } else if (c == ',') {
                    fields.add(current.toString());
                    current.setLength(0);
                } else {
                    current.append(c);
                }
            }
        }
        fields.add(current.toString());
        return fields;
    }
}
