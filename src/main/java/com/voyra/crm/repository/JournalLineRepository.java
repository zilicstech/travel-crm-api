package com.voyra.crm.repository;

import com.voyra.crm.entity.JournalLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface JournalLineRepository extends JpaRepository<JournalLine, String> {

    List<JournalLine> findByJournalEntryIdOrderByLineNo(String journalEntryId);

    List<JournalLine> findByJournalEntryIdIn(List<String> journalEntryIds);

    List<JournalLine> findByAccountCode(String accountCode);

    /**
     * Lines of a POSTED entry whose entry date falls in [from, to] - the source query for the
     * Trial Balance and P&L (ACCOUNTING_EXPANSION_ARCHITECTURE.md §1.9). A theta join, not a JPA
     * association (blueprint §8.4 - flat FK columns only), so this reads as a cross join filtered
     * to matching ids; Postgres treats it exactly like an inner join.
     */
    @Query("SELECT jl FROM JournalLine jl, JournalEntry je "
            + "WHERE jl.journalEntryId = je.id AND je.status = com.voyra.crm.enums.JournalStatus.POSTED "
            + "AND je.entryDate BETWEEN :from AND :to")
    List<JournalLine> findPostedLinesBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /** Same shape, cumulative through a single as-of date - the Balance Sheet's source query. */
    @Query("SELECT jl FROM JournalLine jl, JournalEntry je "
            + "WHERE jl.journalEntryId = je.id AND je.status = com.voyra.crm.enums.JournalStatus.POSTED "
            + "AND je.entryDate <= :asOf")
    List<JournalLine> findPostedLinesAsOf(@Param("asOf") LocalDate asOf);
}
