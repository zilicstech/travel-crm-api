package com.voyra.crm.service;

import com.voyra.crm.entity.JournalEntry;
import com.voyra.crm.entity.JournalLine;
import com.voyra.crm.enums.JournalStatus;
import com.voyra.crm.repository.JournalEntryRepository;
import com.voyra.crm.repository.JournalLineRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * Rule 1.7.3's read - runs on whichever tenant schema {@link com.voyra.crm.context.TenantContext}
 * currently points at, in its own fresh transaction (same reasoning as
 * {@code TenantScopedReadService}: must live on its own bean, since a scheduled job has no
 * request-scoped tenant transaction to join in the first place). Read-only: this never repairs
 * anything, it only names what is wrong.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LedgerIntegrityCheckService {

    private final JournalEntryRepository journalEntryRepository;
    private final JournalLineRepository journalLineRepository;

    public record IntegrityResult(int entriesChecked, List<String> unbalancedEntryNumbers, BigDecimal trialBalanceNet) {
        public boolean isClean() {
            return unbalancedEntryNumbers.isEmpty() && trialBalanceNet.compareTo(BigDecimal.ZERO) == 0;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public IntegrityResult check() {
        List<JournalEntry> posted = journalEntryRepository.findByStatus(JournalStatus.POSTED);
        List<String> unbalanced = new java.util.ArrayList<>();
        BigDecimal netDebit = BigDecimal.ZERO;
        BigDecimal netCredit = BigDecimal.ZERO;

        for (JournalEntry entry : posted) {
            List<JournalLine> lines = journalLineRepository.findByJournalEntryIdOrderByLineNo(entry.getId());
            BigDecimal debit = lines.stream().map(JournalLine::getDebitAmountInr).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal credit = lines.stream().map(JournalLine::getCreditAmountInr).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (debit.compareTo(credit) != 0) {
                unbalanced.add(entry.getEntryNumber());
            }
            netDebit = netDebit.add(debit);
            netCredit = netCredit.add(credit);
        }

        return new IntegrityResult(posted.size(), unbalanced, netDebit.subtract(netCredit));
    }
}
