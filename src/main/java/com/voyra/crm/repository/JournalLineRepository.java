package com.voyra.crm.repository;

import com.voyra.crm.entity.JournalLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Every query here reads entries of EITHER status. A reversal is a new POSTED entry whose lines
 * are the original's with debit/credit swapped, and the original is then marked REVERSED - the
 * pair nets to zero only when both are counted. Filtering to POSTED would keep the reversal and
 * drop the original, booking the negative of the original instead of nothing (this is exactly
 * what the monthly forex revaluation's reverse-then-repost cycle would have hit). It also matches
 * {@code ControlAccountReconciliationService}, which reads {@link #findByAccountCode} unfiltered.
 */
@Repository
public interface JournalLineRepository extends JpaRepository<JournalLine, String> {

    List<JournalLine> findByJournalEntryIdOrderByLineNo(String journalEntryId);

    List<JournalLine> findByJournalEntryIdIn(List<String> journalEntryIds);

    List<JournalLine> findByAccountCode(String accountCode);

    /**
     * Lines of every entry whose entry date falls in [from, to] - the source query for the
     * Trial Balance and P&L (ACCOUNTING_EXPANSION_ARCHITECTURE.md §1.9). A theta join, not a JPA
     * association (blueprint §8.4 - flat FK columns only), so this reads as a cross join filtered
     * to matching ids; Postgres treats it exactly like an inner join.
     */
    @Query("SELECT jl FROM JournalLine jl, JournalEntry je "
            + "WHERE jl.journalEntryId = je.id AND je.entryDate BETWEEN :from AND :to")
    List<JournalLine> findLedgerLinesBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /** Same shape, cumulative through a single as-of date - the Balance Sheet's source query. */
    @Query("SELECT jl FROM JournalLine jl, JournalEntry je "
            + "WHERE jl.journalEntryId = je.id AND je.entryDate <= :asOf")
    List<JournalLine> findLedgerLinesAsOf(@Param("asOf") LocalDate asOf);

    /** One account's lines in [from, to] - the General Ledger drill-down's source query. */
    @Query("SELECT jl FROM JournalLine jl, JournalEntry je "
            + "WHERE jl.journalEntryId = je.id AND jl.accountCode = :accountCode "
            + "AND je.entryDate BETWEEN :from AND :to")
    List<JournalLine> findAccountLinesBetween(@Param("accountCode") String accountCode,
                                              @Param("from") LocalDate from, @Param("to") LocalDate to);

    /** One account's debit total strictly before a date - half of its opening balance. */
    @Query("SELECT COALESCE(SUM(jl.debitAmountInr), 0) FROM JournalLine jl, JournalEntry je "
            + "WHERE jl.journalEntryId = je.id AND jl.accountCode = :accountCode AND je.entryDate < :before")
    BigDecimal sumDebitBefore(@Param("accountCode") String accountCode, @Param("before") LocalDate before);

    /** One account's credit total strictly before a date - the other half of its opening balance. */
    @Query("SELECT COALESCE(SUM(jl.creditAmountInr), 0) FROM JournalLine jl, JournalEntry je "
            + "WHERE jl.journalEntryId = je.id AND jl.accountCode = :accountCode AND je.entryDate < :before")
    BigDecimal sumCreditBefore(@Param("accountCode") String accountCode, @Param("before") LocalDate before);
}
