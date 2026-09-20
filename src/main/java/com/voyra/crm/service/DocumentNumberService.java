package com.voyra.crm.service;

import com.voyra.crm.enums.DocumentKind;
import com.voyra.crm.repository.DocumentNumberSequenceRepository;
import com.voyra.crm.util.FinancialYear;
import com.voyra.crm.util.UniqueIdResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Allocates {@code {prefix}/{fy}/{seq}} document numbers (e.g. {@code INV/2026-27/0001}),
 * concurrency-safe under Postgres. The increment is a raw {@code UPDATE ... RETURNING}
 * statement, not a JPA entity save: Postgres takes a row-level lock on the target row for the
 * rest of the caller's transaction, so concurrent issues on the same (kind, financial year)
 * series serialize on that row - no duplicate, no gap. A Postgres {@code SEQUENCE} is
 * deliberately not used: sequences are non-transactional, so a rolled-back issue would burn a
 * number permanently, and a GST invoice book must stay consecutive.
 *
 * <p>Callers must invoke {@link #next} only inside the same transaction that actually commits
 * the issued document - see the no-gap rule in {@code util.InvoiceLifecyclePolicy}'s javadoc:
 * a number is allocated only at the DRAFT -&gt; ISSUED transition.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentNumberService {

    private final JdbcTemplate jdbcTemplate;
    private final DocumentNumberSequenceRepository documentNumberSequenceRepository;

    @Transactional
    public String next(DocumentKind kind, LocalDate asOf) {
        String financialYear = FinancialYear.of(asOf);
        String prefix = defaultPrefix(kind);

        Long value = incrementAndReturn(kind, financialYear);
        if (value == null) {
            seedRow(kind, financialYear, prefix);
            value = incrementAndReturn(kind, financialYear);
            if (value == null) {
                throw new IllegalStateException(
                        "Unable to allocate a document number for " + kind + "/" + financialYear);
            }
        }
        return "%s/%s/%04d".formatted(prefix, financialYear, value);
    }

    /** Null means no row exists yet for this (kind, financialYear) pair. */
    private Long incrementAndReturn(DocumentKind kind, String financialYear) {
        List<Long> rows = jdbcTemplate.query("""
                UPDATE document_number_sequence
                   SET last_value = last_value + 1, updated_at = ?
                 WHERE document_kind = ? AND financial_year = ?
                RETURNING last_value
                """,
                (rs, rowNum) -> rs.getLong(1),
                LocalDateTime.now(), kind.name(), financialYear);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** ON CONFLICT DO NOTHING: a concurrent caller may win the insert race - the retry in {@link #next} then finds the row either way. */
    private void seedRow(DocumentKind kind, String financialYear, String prefix) {
        String id = UniqueIdResolver.resolve(documentNumberSequenceRepository::existsById);
        int inserted = jdbcTemplate.update("""
                INSERT INTO document_number_sequence (id, document_kind, financial_year, prefix, last_value, padding, updated_at)
                VALUES (?, ?, ?, ?, 0, 4, ?)
                ON CONFLICT (document_kind, financial_year) DO NOTHING
                """,
                id, kind.name(), financialYear, prefix, LocalDateTime.now());
        if (inserted > 0) {
            log.info("Document number sequence seeded: kind={}, financialYear={}, prefix={}", kind, financialYear, prefix);
        }
    }

    /** Fixed per document kind - not admin-configurable in this release; see the architecture note on why. */
    private String defaultPrefix(DocumentKind kind) {
        return switch (kind) {
            case TAX_INVOICE -> "INV";
            case PROFORMA -> "PI";
            case RECEIPT -> "RCP";
            case CREDIT_NOTE -> "CN";
            case PAYMENT_VOUCHER -> "PV";
        };
    }
}
