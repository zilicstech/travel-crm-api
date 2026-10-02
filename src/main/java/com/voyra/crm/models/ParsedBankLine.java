package com.voyra.crm.models;

import com.voyra.crm.enums.BankTransactionDirection;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One line a {@code StatementParser} extracted from a raw statement file, before anything is
 * persisted. Pure data - no entity, no id, so a parser implementation stays free of Spring and
 * JPA and is unit-testable against a bare fixture file (Rule 4.2.1).
 */
public record ParsedBankLine(
        LocalDate txnDate,
        LocalDate valueDate,
        String description,
        String bankReference,
        BigDecimal amount,
        BankTransactionDirection direction,
        BigDecimal runningBalance
) {
}
