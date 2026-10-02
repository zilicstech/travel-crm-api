package com.voyra.crm.service;

import com.voyra.crm.dto.ControlAccountReconciliationRowResponse;
import com.voyra.crm.entity.JournalEntry;
import com.voyra.crm.entity.LedgerAccount;
import com.voyra.crm.enums.JournalPurpose;
import com.voyra.crm.enums.JournalSourceType;
import com.voyra.crm.enums.LedgerAccountType;
import com.voyra.crm.models.JournalPosting;
import com.voyra.crm.repository.JournalEntryRepository;
import com.voyra.crm.repository.LedgerAccountRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Gate 2.1's catch-up half: given a reconciliation report showing real variance (the expected
 * state for a tenant whose history predates the GL), closes each account against 3100 and proves
 * the posting both balances and is idempotent - a second call for an already-posted account must
 * not post again. Found and tested after posting a real advance payment against a live QA tenant
 * surfaced the exact scenario this service exists to fix.
 */
@ExtendWith(MockitoExtension.class)
class OpeningBalanceBackfillServiceTest {

    @Mock
    private ControlAccountReconciliationService reconciliationService;
    @Mock
    private LedgerAccountRepository ledgerAccountRepository;
    @Mock
    private JournalEntryRepository journalEntryRepository;
    @Mock
    private JournalService journalService;

    @InjectMocks
    private OpeningBalanceBackfillService backfillService;

    private static ControlAccountReconciliationRowResponse row(String code, String name, String gl, String subsidiary) {
        BigDecimal glBalance = new BigDecimal(gl);
        BigDecimal subsidiaryTotal = new BigDecimal(subsidiary);
        return ControlAccountReconciliationRowResponse.builder()
                .accountCode(code).accountName(name)
                .glBalanceInr(glBalance).subsidiaryTotalInr(subsidiaryTotal)
                .varianceInr(glBalance.subtract(subsidiaryTotal))
                .build();
    }

    @Test
    void closesAnAssetControlAccountsVarianceByDebitingItAndCreditingOpeningBalanceEquity() {
        when(reconciliationService.reconcile()).thenReturn(List.of(
                row("1200", "Accounts Receivable", "0.00", "214750.28")));
        when(ledgerAccountRepository.findByCode("1200"))
                .thenReturn(Optional.of(LedgerAccount.builder().code("1200").accountType(LedgerAccountType.ASSET).build()));
        when(journalEntryRepository.findBySourceTypeAndSourceIdAndPurpose(
                JournalSourceType.OPENING_BALANCE, "1200", JournalPurpose.OPENING_BALANCE_POSTED))
                .thenReturn(Optional.empty());
        when(journalService.post(any())).thenReturn(JournalEntry.builder().id("JE1").build());

        int posted = backfillService.backfillCurrentTenant();

        assertThat(posted).isEqualTo(1);
        ArgumentCaptor<JournalPosting> captor = ArgumentCaptor.forClass(JournalPosting.class);
        verify(journalService).post(captor.capture());
        JournalPosting posting = captor.getValue();

        assertThat(posting.sourceType()).isEqualTo(JournalSourceType.OPENING_BALANCE);
        assertThat(posting.sourceId()).isEqualTo("1200");
        assertThat(posting.purpose()).isEqualTo(JournalPurpose.OPENING_BALANCE_POSTED);
        assertThat(posting.lines()).hasSize(2);
        assertThat(posting.lines().get(0).accountCode()).isEqualTo("1200");
        assertThat(posting.lines().get(0).debitAmount()).isEqualByComparingTo("214750.28");
        assertThat(posting.lines().get(1).accountCode()).isEqualTo("3100");
        assertThat(posting.lines().get(1).creditAmount()).isEqualByComparingTo("214750.28");

        BigDecimal totalDebit = posting.lines().stream().map(l -> l.debitAmount()).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCredit = posting.lines().stream().map(l -> l.creditAmount()).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(totalDebit).isEqualByComparingTo(totalCredit);
    }

    @Test
    void closesALiabilityControlAccountsVarianceByCreditingItAndDebitingOpeningBalanceEquity() {
        when(reconciliationService.reconcile()).thenReturn(List.of(
                row("2200", "Accounts Payable", "0.00", "14312.50")));
        when(ledgerAccountRepository.findByCode("2200"))
                .thenReturn(Optional.of(LedgerAccount.builder().code("2200").accountType(LedgerAccountType.LIABILITY).build()));
        when(journalEntryRepository.findBySourceTypeAndSourceIdAndPurpose(
                JournalSourceType.OPENING_BALANCE, "2200", JournalPurpose.OPENING_BALANCE_POSTED))
                .thenReturn(Optional.empty());
        when(journalService.post(any())).thenReturn(JournalEntry.builder().id("JE2").build());

        backfillService.backfillCurrentTenant();

        ArgumentCaptor<JournalPosting> captor = ArgumentCaptor.forClass(JournalPosting.class);
        verify(journalService).post(captor.capture());
        JournalPosting posting = captor.getValue();

        assertThat(posting.lines().get(0).accountCode()).isEqualTo("2200");
        assertThat(posting.lines().get(0).creditAmount()).isEqualByComparingTo("14312.50");
        assertThat(posting.lines().get(1).accountCode()).isEqualTo("3100");
        assertThat(posting.lines().get(1).debitAmount()).isEqualByComparingTo("14312.50");
    }

    @Test
    void skipsAnAccountWhoseVarianceIsAlreadyZero() {
        when(reconciliationService.reconcile()).thenReturn(List.of(
                row("2110", "Client Advances Held", "0.00", "0.00")));

        int posted = backfillService.backfillCurrentTenant();

        assertThat(posted).isZero();
        verify(journalService, never()).post(any());
    }

    @Test
    void skipsAnAccountThatAlreadyHasAnOpeningBalanceJournalEvenIfVarianceIsStillNonZero() {
        when(reconciliationService.reconcile()).thenReturn(List.of(
                row("1300", "Supplier Advances", "5000.00", "15000.00")));
        when(journalEntryRepository.findBySourceTypeAndSourceIdAndPurpose(
                JournalSourceType.OPENING_BALANCE, "1300", JournalPurpose.OPENING_BALANCE_POSTED))
                .thenReturn(Optional.of(JournalEntry.builder().id("ALREADY").build()));

        int posted = backfillService.backfillCurrentTenant();

        assertThat(posted).isZero();
        verify(journalService, never()).post(any());
    }
}
