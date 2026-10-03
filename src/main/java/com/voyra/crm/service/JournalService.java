package com.voyra.crm.service;

import com.voyra.crm.dto.JournalEntryResponse;
import com.voyra.crm.dto.JournalLineResponse;
import com.voyra.crm.dto.JournalPostRequest;
import com.voyra.crm.entity.JournalEntry;
import com.voyra.crm.entity.JournalLine;
import com.voyra.crm.entity.LedgerAccount;
import com.voyra.crm.enums.AuditEntityType;
import com.voyra.crm.enums.DocumentKind;
import com.voyra.crm.enums.JournalSourceType;
import com.voyra.crm.enums.JournalStatus;
import com.voyra.crm.models.JournalLinePosting;
import com.voyra.crm.models.JournalPosting;
import com.voyra.crm.repository.JournalEntryRepository;
import com.voyra.crm.repository.JournalLineRepository;
import com.voyra.crm.repository.LedgerAccountRepository;
import com.voyra.crm.security.SecurityContextUtil;
import com.voyra.crm.util.FinancialYear;
import com.voyra.crm.util.UniqueIdResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The single writer of {@code journal_entry}/{@code journal_line} - ACCOUNTING_EXPANSION_ARCHITECTURE.md
 * §1.6. Meant to be called from INSIDE the same transaction as the business operation that caused
 * the posting (Rule 1.8.1): a caller such as {@code InvoiceDocumentService#issue} already runs in
 * one {@code @Transactional} method and calls {@code journalService.post(...)} immediately beside
 * its existing subsidiary-ledger write - this method intentionally carries no propagation override,
 * so Spring's default (REQUIRED) joins that transaction rather than opening a new one. NEVER call
 * this from an event listener, {@code @Async}, or anything deferred (Rule 1.8.2) - a deferred
 * write turns a business-operation rollback into a silent divergence between the documents and the
 * ledger, the worst failure mode this subsystem can have.
 *
 * <p>Append-only. There is no update path and no delete path here, in any repository, or in any
 * controller (Rule 1.6.2) - a correction is {@link #reverse}, which posts a NEW balanced entry
 * with every line's debit and credit swapped and marks the original {@link JournalStatus#REVERSED}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class JournalService {

    private final JournalEntryRepository journalEntryRepository;
    private final JournalLineRepository journalLineRepository;
    private final LedgerAccountRepository ledgerAccountRepository;
    private final DocumentNumberService documentNumberService;
    private final AuditService auditService;

    @Transactional
    public JournalEntry post(JournalPosting posting) {
        if (posting.lines() == null || posting.lines().size() < 2) {
            throw new IllegalArgumentException("A journal entry needs at least two lines");
        }

        BigDecimal totalDebitInr = BigDecimal.ZERO;
        BigDecimal totalCreditInr = BigDecimal.ZERO;
        List<JournalLine> lines = new ArrayList<>();
        int lineNo = 1;
        for (JournalLinePosting lp : posting.lines()) {
            BigDecimal debit = nz(lp.debitAmount());
            BigDecimal credit = nz(lp.creditAmount());
            if (debit.compareTo(BigDecimal.ZERO) > 0 && credit.compareTo(BigDecimal.ZERO) > 0) {
                throw new IllegalArgumentException("Journal line " + lineNo + " carries both a debit and a credit - a line must be one or the other");
            }
            if (debit.compareTo(BigDecimal.ZERO) == 0 && credit.compareTo(BigDecimal.ZERO) == 0) {
                throw new IllegalArgumentException("Journal line " + lineNo + " carries neither a debit nor a credit");
            }

            LedgerAccount account = ledgerAccountRepository.findByCode(lp.accountCode())
                    .orElseThrow(() -> new IllegalArgumentException("Unknown ledger account code: " + lp.accountCode()));
            // Rule 1.3.3 - a control account may never be the target of a MANUAL entry. A
            // non-manual posting (the posting-rule table) is the only legitimate way money moves
            // through a control account, and every one of those sites is in this codebase, not
            // typed by a human.
            if (Boolean.TRUE.equals(account.getIsControl()) && posting.sourceType() == JournalSourceType.MANUAL) {
                throw new IllegalArgumentException(
                        "Account " + lp.accountCode() + " (" + account.getName() + ") is a control account "
                                + "and cannot be the target of a manual journal entry");
            }

            BigDecimal fxRate = lp.fxRateToInr() != null ? lp.fxRateToInr() : BigDecimal.ONE;
            BigDecimal debitInr = debit.multiply(fxRate).setScale(2, RoundingMode.HALF_UP);
            BigDecimal creditInr = credit.multiply(fxRate).setScale(2, RoundingMode.HALF_UP);
            totalDebitInr = totalDebitInr.add(debitInr);
            totalCreditInr = totalCreditInr.add(creditInr);

            lines.add(JournalLine.builder()
                    .id(UniqueIdResolver.resolve(journalLineRepository::existsById))
                    .lineNo(lineNo++)
                    .accountCode(lp.accountCode())
                    .partyType(lp.partyType())
                    .partyId(lp.partyId())
                    .currencyCode(lp.currencyCode() != null ? lp.currencyCode() : "INR")
                    .fxRateToInr(fxRate)
                    .debitAmount(debit)
                    .creditAmount(credit)
                    .debitAmountInr(debitInr)
                    .creditAmountInr(creditInr)
                    .narration(lp.narration())
                    .build());
        }

        // Rule 1.6.1 - no tolerance. A cross-row sum cannot be a SQL CHECK constraint, so this
        // assertion (plus the nightly LedgerIntegrityJob) is the entire guarantee.
        if (totalDebitInr.compareTo(totalCreditInr) != 0) {
            throw new IllegalStateException(
                    "Journal entry does not balance: debits " + totalDebitInr + " vs credits " + totalCreditInr);
        }

        String entryId = UniqueIdResolver.resolve(journalEntryRepository::existsById);
        String entryNumber = documentNumberService.next(DocumentKind.JOURNAL, posting.entryDate());

        JournalEntry entry = JournalEntry.builder()
                .id(entryId)
                .entryNumber(entryNumber)
                .entryDate(posting.entryDate())
                .financialYear(FinancialYear.of(posting.entryDate()))
                .sourceType(posting.sourceType())
                .sourceId(posting.sourceId())
                .purpose(posting.purpose())
                .narration(posting.narration())
                .bookingId(posting.bookingId())
                .branchId(posting.branchId())
                .status(JournalStatus.POSTED)
                .createdAt(LocalDateTime.now())
                .createdBy(currentUserId())
                .build();

        for (JournalLine line : lines) {
            line.setJournalEntryId(entryId);
        }

        // Idempotency (Rule 1.6.3) is the unique index uq_journal_entry_source - no in-code
        // existence pre-check, matching V21's convention for customer_ledger_entry.
        journalEntryRepository.save(entry);
        journalLineRepository.saveAll(lines);

        if (posting.sourceType() == JournalSourceType.MANUAL) {
            auditService.recordCreate(AuditEntityType.JOURNAL_ENTRY, entry.getId(), entry.getEntryNumber());
        }
        log.info("Journal entry posted: id={}, number={}, purpose={}, debit={}, credit={}",
                entry.getId(), entry.getEntryNumber(), posting.purpose(), totalDebitInr, totalCreditInr);
        return entry;
    }

    @Transactional
    public JournalEntry reverse(String entryId, String reason) {
        JournalEntry original = journalEntryRepository.findById(entryId)
                .orElseThrow(() -> new IllegalArgumentException("Journal entry not found: " + entryId));
        if (original.getStatus() == JournalStatus.REVERSED) {
            throw new IllegalStateException("This entry has already been reversed");
        }

        List<JournalLine> originalLines = journalLineRepository.findByJournalEntryIdOrderByLineNo(entryId);
        List<JournalLinePosting> reversedLines = originalLines.stream()
                .map(l -> new JournalLinePosting(l.getAccountCode(), l.getPartyType(), l.getPartyId(),
                        l.getCurrencyCode(), l.getFxRateToInr(), l.getCreditAmount(), l.getDebitAmount(), l.getNarration()))
                .toList();

        JournalPosting reversal = new JournalPosting(
                java.time.LocalDate.now(), original.getSourceType(), null, original.getPurpose(),
                "Reversal of " + original.getEntryNumber() + (reason != null && !reason.isBlank() ? " - " + reason : ""),
                original.getBookingId(), original.getBranchId(), reversedLines);

        JournalEntry reversalEntry = postReversal(reversal, entryId);

        original.setStatus(JournalStatus.REVERSED);
        journalEntryRepository.save(original);

        auditService.recordUpdate(AuditEntityType.JOURNAL_ENTRY, original.getId(), original.getEntryNumber(), List.of());
        log.info("Journal entry reversed: originalId={}, reversalId={}", entryId, reversalEntry.getId());
        return reversalEntry;
    }

    /**
     * Identical to {@link #post} except it stamps {@code reverses_entry_id} and skips the
     * MANUAL-only idempotency source id (a reversal always carries {@code source_id = null},
     * since it is not itself the direct product of a re-postable business event).
     */
    private JournalEntry postReversal(JournalPosting posting, String reversesEntryId) {
        JournalEntry entry = post(posting);
        entry.setReversesEntryId(reversesEntryId);
        journalEntryRepository.save(entry);
        return entry;
    }

    /** The manual-journal HTTP endpoint's entry point - converts the validated request DTO into the internal posting model and reports back. */
    @Transactional
    public JournalEntryResponse postManual(JournalPostRequest request) {
        List<JournalLinePosting> lines = request.getLines().stream()
                .map(l -> new JournalLinePosting(l.getAccountCode(), l.getPartyType(), l.getPartyId(),
                        l.getCurrencyCode() != null ? l.getCurrencyCode() : "INR",
                        l.getFxRateToInr() != null ? l.getFxRateToInr() : BigDecimal.ONE,
                        nz(l.getDebitAmount()), nz(l.getCreditAmount()), l.getNarration()))
                .toList();
        JournalPosting posting = new JournalPosting(request.getEntryDate(), request.getSourceType(),
                request.getSourceId(), request.getPurpose(), request.getNarration(),
                request.getBookingId(), request.getBranchId(), lines);
        return toResponse(post(posting));
    }

    @Transactional(readOnly = true)
    public JournalEntryResponse get(String id) {
        JournalEntry entry = journalEntryRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Journal entry not found: " + id));
        return toResponse(entry);
    }

    public JournalEntryResponse toResponse(JournalEntry entry) {
        return toResponse(entry, journalLineRepository.findByJournalEntryIdOrderByLineNo(entry.getId()));
    }

    /** Batch form for the journal register - one line query for every entry, not one per entry. */
    @Transactional(readOnly = true)
    public List<JournalEntryResponse> toResponses(List<JournalEntry> entries) {
        if (entries.isEmpty()) {
            return List.of();
        }
        Map<String, List<JournalLine>> linesByEntry = journalLineRepository
                .findByJournalEntryIdIn(entries.stream().map(JournalEntry::getId).toList()).stream()
                .sorted(Comparator.comparing(JournalLine::getLineNo))
                .collect(Collectors.groupingBy(JournalLine::getJournalEntryId));
        return entries.stream()
                .map(e -> toResponse(e, linesByEntry.getOrDefault(e.getId(), List.of())))
                .toList();
    }

    private JournalEntryResponse toResponse(JournalEntry entry, List<JournalLine> entryLines) {
        List<JournalLineResponse> lines = entryLines.stream()
                .map(l -> JournalLineResponse.builder()
                        .id(l.getId()).lineNo(l.getLineNo()).accountCode(l.getAccountCode())
                        .partyType(l.getPartyType()).partyId(l.getPartyId())
                        .currencyCode(l.getCurrencyCode()).fxRateToInr(l.getFxRateToInr())
                        .debitAmount(l.getDebitAmount()).creditAmount(l.getCreditAmount())
                        .debitAmountInr(l.getDebitAmountInr()).creditAmountInr(l.getCreditAmountInr())
                        .narration(l.getNarration())
                        .build())
                .toList();
        return JournalEntryResponse.builder()
                .id(entry.getId()).entryNumber(entry.getEntryNumber()).entryDate(entry.getEntryDate())
                .financialYear(entry.getFinancialYear()).sourceType(entry.getSourceType()).sourceId(entry.getSourceId())
                .purpose(entry.getPurpose()).narration(entry.getNarration()).bookingId(entry.getBookingId())
                .branchId(entry.getBranchId()).status(entry.getStatus()).reversesEntryId(entry.getReversesEntryId())
                .createdAt(entry.getCreatedAt()).createdBy(entry.getCreatedBy())
                .lines(lines)
                .build();
    }

    private static BigDecimal nz(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    private String currentUserId() {
        return SecurityContextUtil.getCurrentUserOrThrow().userId();
    }
}
