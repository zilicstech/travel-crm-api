package com.voyra.crm.service;

import com.voyra.crm.entity.BankStatementProfile;
import com.voyra.crm.models.StatementParseResult;

/**
 * Rule 4.2.1 - one implementation per {@code bank_statement_profile.file_format}. Deliberately
 * pure: no Spring, no repository, no I/O, so a format's parsing logic is unit-testable against a
 * bare fixture file without standing up any infrastructure.
 */
public interface StatementParser {

    /** The {@code file_format} value this parser handles, e.g. {@code "CSV"}. */
    String supportedFormat();

    StatementParseResult parse(byte[] fileContent, BankStatementProfile profile);
}
