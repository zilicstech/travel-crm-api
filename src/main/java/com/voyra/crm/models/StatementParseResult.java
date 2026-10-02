package com.voyra.crm.models;

import java.util.List;

/** Output of {@code StatementParser#parse} - a malformed row is reported in {@link #rowErrors}, never silently dropped. */
public record StatementParseResult(
        List<ParsedBankLine> lines,
        List<String> rowErrors
) {
}
