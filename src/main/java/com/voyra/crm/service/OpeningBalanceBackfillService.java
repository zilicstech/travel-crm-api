package com.voyra.crm.service;

import com.voyra.crm.dto.ControlAccountReconciliationRowResponse;
import com.voyra.crm.entity.JournalEntry;
import com.voyra.crm.entity.LedgerAccount;
import com.voyra.crm.enums.JournalPurpose;
import com.voyra.crm.enums.JournalSourceType;
import com.voyra.crm.enums.LedgerAccountType;
import com.voyra.crm.enums.SystemAccount;
import com.voyra.crm.models.JournalLinePosting;
import com.voyra.crm.models.JournalPosting;
import com.voyra.crm.repository.JournalEntryRepository;
import com.voyra.crm.repository.LedgerAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One-time, per-tenant, per-control-account catch-up for the GL cutover gap: every tenant's
 * historical AR/AP/advance balances predate the GL (Epic E) and never posted a journal, so
 * {@link ControlAccountReconciliationService#reconcile} legitimately shows a non-zero variance
 * against real money until this runs - exactly the risk ACCOUNTING_EXPANSION_ARCHITECTURE.md's
 * Risk 3 and the implementation plan's Gate 2.1 describe. Posts posting-rule-table row 17: each
 * control account's variance is closed against {@code 3100 Opening Balance Equity}, dated the day
 * this runs (a "dated cutover with derived opening balances," per the architecture doc's own
 * preference over a full historical journal-by-journal backfill).
 *
 * <p>Deliberately NOT wired to an always-on {@code ApplicationRunner} - unlike chart-of-accounts
 * seeding (purely additive, safe to repeat on every boot), this posts real financial journal
 * entries once per account, and the amount it posts depends on whatever the subsidiary ledgers
 * read at the moment it runs. See {@code config.OpeningBalanceBackfillRunner} for the explicit,
 * opt-in trigger (default OFF).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OpeningBalanceBackfillService {

    private final ControlAccountReconciliationService reconciliationService;
    private final LedgerAccountRepository ledgerAccountRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final JournalService journalService;

    /**
     * Idempotent per account: skips any control account that already has an
     * {@code OPENING_BALANCE_POSTED} entry (whether from a prior run of this service or a manual
     * journal), and skips any account whose variance is already zero. Returns the number of
     * accounts actually posted, for the caller to log.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int backfillCurrentTenant() {
        int posted = 0;
        for (ControlAccountReconciliationRowResponse row : reconciliationService.reconcile()) {
            if (row.getVarianceInr().compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }
            if (journalEntryRepository.findBySourceTypeAndSourceIdAndPurpose(
                    JournalSourceType.OPENING_BALANCE, row.getAccountCode(), JournalPurpose.OPENING_BALANCE_POSTED).isPresent()) {
                log.info("Opening balance already posted for account {} - skipping", row.getAccountCode());
                continue;
            }
            postOpeningBalance(row);
            posted++;
        }
        return posted;
    }

    private void postOpeningBalance(ControlAccountReconciliationRowResponse row) {
        LedgerAccount account = ledgerAccountRepository.findByCode(row.getAccountCode())
                .orElseThrow(() -> new IllegalStateException("Control account not found: " + row.getAccountCode()));
        // variance = glBalance - subsidiaryTotal; delta is how much the GL must move to close it.
        BigDecimal delta = row.getSubsidiaryTotalInr().subtract(row.getGlBalanceInr());
        BigDecimal amount = delta.abs();
        boolean increasing = delta.signum() > 0;
        String narration = "Opening balance - " + row.getAccountName() + " catch-up to subsidiary ledger as of " + LocalDate.now();

        JournalLinePosting accountLine;
        JournalLinePosting equityLine;
        if (account.getAccountType() == LedgerAccountType.ASSET) {
            accountLine = increasing ? JournalLinePosting.debit(row.getAccountCode(), amount, narration)
                    : JournalLinePosting.credit(row.getAccountCode(), amount, narration);
            equityLine = increasing ? JournalLinePosting.credit(SystemAccount.OPENING_BALANCE_EQUITY.code(), amount, narration)
                    : JournalLinePosting.debit(SystemAccount.OPENING_BALANCE_EQUITY.code(), amount, narration);
        } else {
            accountLine = increasing ? JournalLinePosting.credit(row.getAccountCode(), amount, narration)
                    : JournalLinePosting.debit(row.getAccountCode(), amount, narration);
            equityLine = increasing ? JournalLinePosting.debit(SystemAccount.OPENING_BALANCE_EQUITY.code(), amount, narration)
                    : JournalLinePosting.credit(SystemAccount.OPENING_BALANCE_EQUITY.code(), amount, narration);
        }

        JournalEntry entry = journalService.post(new JournalPosting(
                LocalDate.now(), JournalSourceType.OPENING_BALANCE, row.getAccountCode(),
                JournalPurpose.OPENING_BALANCE_POSTED, narration, null, null,
                java.util.List.of(accountLine, equityLine)));
        log.info("Opening balance posted: account={}, amount={}, entryId={}", row.getAccountCode(), amount, entry.getId());
    }
}
