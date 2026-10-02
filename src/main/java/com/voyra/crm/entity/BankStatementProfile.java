package com.voyra.crm.entity;

import com.voyra.crm.enums.BankAmountConvention;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Rule 4.1.1 - "CSV" is not a format. Every bank emits a different column order, date format and
 * sign convention; an import is rejected without a profile. {@link #columnMap} is a JSON object
 * (stored as text, parsed by {@code CsvStatementParser}) mapping the parser's logical fields -
 * {@code date}, {@code description}, {@code reference}, {@code amount} (or {@code debit}/{@code
 * credit} under {@link BankAmountConvention#DEBIT_CREDIT_COLUMNS}) - to this bank's actual CSV
 * header names. {@link #fileFormat} is a free string, not an enum, so a future OFX/QIF parser slots
 * in without a migration - only a {@code CSV} implementation ships now (architecture doc §9).
 */
@Entity
@Table(name = "bank_statement_profile")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class BankStatementProfile {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "bank_name", nullable = false, length = 120)
    private String bankName;

    @Column(name = "file_format", nullable = false, length = 20)
    @Builder.Default
    private String fileFormat = "CSV";

    @Column(name = "date_format", nullable = false, length = 30)
    private String dateFormat;

    @Column(name = "column_map", nullable = false, columnDefinition = "TEXT")
    private String columnMap;

    @Enumerated(EnumType.STRING)
    @Column(name = "amount_convention", nullable = false, length = 30)
    private BankAmountConvention amountConvention;

    @Column(name = "created_date")
    private LocalDateTime createdDate;
}
