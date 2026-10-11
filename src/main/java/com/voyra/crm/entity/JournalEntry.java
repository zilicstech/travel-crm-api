package com.voyra.crm.entity;

import com.voyra.crm.enums.JournalPurpose;
import com.voyra.crm.enums.JournalSourceType;
import com.voyra.crm.enums.JournalStatus;
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

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * The header of one balanced double-entry posting. Append-only - {@code service.JournalService}
 * is the sole writer and there is no update or delete path anywhere (Rule 1.6.2). A correction is
 * {@link #reversesEntryId} on a NEW entry whose lines are the original's with debit/credit
 * swapped; the original is then marked {@link JournalStatus#REVERSED}, never edited.
 *
 * <p>The unique index on {@code (source_type, source_id, purpose)} (null {@code source_id}
 * excepted, for MANUAL) is the whole idempotency guarantee - {@code JournalService} does not
 * duplicate that check in code (Rule 1.6.3).
 */
@Entity
@Table(name = "journal_entry")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class JournalEntry {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "entry_number", nullable = false, length = 40)
    private String entryNumber;

    @Column(name = "entry_date", nullable = false)
    private LocalDate entryDate;

    @Column(name = "financial_year", nullable = false, length = 9)
    private String financialYear;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 30)
    private JournalSourceType sourceType;

    /** Usually a 36-char entity id; a period-qualified revaluation source ("&lt;bill id&gt;@&lt;yyyyMM&gt;", V47) needs more. */
    @Column(name = "source_id", length = 50)
    private String sourceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 40)
    private JournalPurpose purpose;

    @Column(name = "narration", nullable = false, length = 255)
    private String narration;

    @Column(name = "booking_id", length = 36)
    private String bookingId;

    @Column(name = "branch_id", length = 36)
    private String branchId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private JournalStatus status;

    @Column(name = "reverses_entry_id", length = 36)
    private String reversesEntryId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "created_by", length = 36)
    private String createdBy;
}
