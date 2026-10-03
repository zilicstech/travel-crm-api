package com.voyra.crm.service;

import com.voyra.crm.dto.AccountLedgerResponse;
import com.voyra.crm.dto.LedgerAccountBalanceResponse;
import com.voyra.crm.entity.JournalEntry;
import com.voyra.crm.entity.JournalLine;
import com.voyra.crm.entity.LedgerAccount;
import com.voyra.crm.enums.JournalPurpose;
import com.voyra.crm.enums.JournalSourceType;
import com.voyra.crm.enums.JournalStatus;
import com.voyra.crm.enums.LedgerAccountType;
import com.voyra.crm.repository.JournalEntryRepository;
import com.voyra.crm.repository.JournalLineRepository;
import com.voyra.crm.repository.LedgerAccountRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GeneralLedgerQueryServiceTest {

    @Mock
    private LedgerAccountRepository ledgerAccountRepository;
    @Mock
    private JournalLineRepository journalLineRepository;
    @Mock
    private JournalEntryRepository journalEntryRepository;
    @Mock
    private JournalService journalService;

    @InjectMocks
    private GeneralLedgerQueryService service;

    private static final LocalDate FROM = LocalDate.of(2026, 9, 1);
    private static final LocalDate TO = LocalDate.of(2026, 9, 30);

    private static JournalLine line(String entryId, int lineNo, String code, String debit, String credit) {
        return JournalLine.builder().id(entryId + lineNo).journalEntryId(entryId).lineNo(lineNo).accountCode(code)
                .currencyCode("INR").debitAmountInr(new BigDecimal(debit)).creditAmountInr(new BigDecimal(credit)).build();
    }

    private static JournalEntry entry(String id, String number, LocalDate date, JournalStatus status) {
        return JournalEntry.builder().id(id).entryNumber(number).entryDate(date).status(status)
                .sourceType(JournalSourceType.INVOICE).purpose(JournalPurpose.values()[0]).narration("Entry " + number).build();
    }

    @Test
    void chartOfAccounts_reportsBalanceInEachAccountsNaturalDirection() {
        when(journalLineRepository.findLedgerLinesAsOf(TO)).thenReturn(List.of(
                line("e1", 1, "1200", "1000", "0"),
                line("e1", 2, "4100", "0", "1000"),
                line("e2", 1, "1200", "0", "250")));
        when(ledgerAccountRepository.findAll()).thenReturn(List.of(
                LedgerAccount.builder().code("4100").name("Package Sales").accountType(LedgerAccountType.INCOME).build(),
                LedgerAccount.builder().code("1200").name("Accounts Receivable").accountType(LedgerAccountType.ASSET).isControl(true).build(),
                LedgerAccount.builder().code("5100").name("Package Cost").accountType(LedgerAccountType.EXPENSE).build()));

        List<LedgerAccountBalanceResponse> rows = service.chartOfAccounts(TO);

        assertThat(rows).extracting(LedgerAccountBalanceResponse::getCode).containsExactly("1200", "4100", "5100");
        assertThat(rows.get(0).getBalanceInr()).isEqualByComparingTo("750");   // asset: debit - credit
        assertThat(rows.get(1).getBalanceInr()).isEqualByComparingTo("1000");  // income: credit - debit
        assertThat(rows.get(2).getBalanceInr()).isEqualByComparingTo("0");     // untouched account still listed
    }

    @Test
    void accountLedger_carriesOpeningBalanceAndRunsBalanceInDateThenNumberOrder() {
        when(ledgerAccountRepository.findByCode("1200")).thenReturn(Optional.of(
                LedgerAccount.builder().code("1200").name("Accounts Receivable").accountType(LedgerAccountType.ASSET).build()));
        when(journalLineRepository.sumDebitBefore("1200", FROM)).thenReturn(new BigDecimal("500"));
        when(journalLineRepository.sumCreditBefore("1200", FROM)).thenReturn(new BigDecimal("100"));
        // Returned out of order on purpose - the service must sort by date, then entry number.
        when(journalLineRepository.findAccountLinesBetween("1200", FROM, TO)).thenReturn(List.of(
                line("e2", 1, "1200", "0", "300"),
                line("e1", 1, "1200", "1000", "0")));
        when(journalEntryRepository.findByIdIn(anyList())).thenReturn(List.of(
                entry("e1", "JNV/2026-27/0001", LocalDate.of(2026, 9, 5), JournalStatus.POSTED),
                entry("e2", "JNV/2026-27/0002", LocalDate.of(2026, 9, 10), JournalStatus.POSTED)));

        AccountLedgerResponse ledger = service.accountLedger("1200", FROM, TO);

        assertThat(ledger.getOpeningBalanceInr()).isEqualByComparingTo("400");
        assertThat(ledger.getLines()).extracting(l -> l.getEntryNumber())
                .containsExactly("JNV/2026-27/0001", "JNV/2026-27/0002");
        assertThat(ledger.getLines().get(0).getRunningBalanceInr()).isEqualByComparingTo("1400");
        assertThat(ledger.getLines().get(1).getRunningBalanceInr()).isEqualByComparingTo("1100");
        assertThat(ledger.getTotalDebitInr()).isEqualByComparingTo("1000");
        assertThat(ledger.getTotalCreditInr()).isEqualByComparingTo("300");
        assertThat(ledger.getClosingBalanceInr()).isEqualByComparingTo("1100");
    }

    @Test
    void accountLedger_aReversedEntryAndItsReversalNetToZero() {
        // Regression guard for the reversal double-count: both the REVERSED original and its
        // POSTED reversal must appear, so the account ends where it started.
        when(ledgerAccountRepository.findByCode("4710")).thenReturn(Optional.of(
                LedgerAccount.builder().code("4710").name("Unrealized Forex Gain").accountType(LedgerAccountType.INCOME).build()));
        when(journalLineRepository.sumDebitBefore(any(), any())).thenReturn(BigDecimal.ZERO);
        when(journalLineRepository.sumCreditBefore(any(), any())).thenReturn(BigDecimal.ZERO);
        when(journalLineRepository.findAccountLinesBetween("4710", FROM, TO)).thenReturn(List.of(
                line("orig", 1, "4710", "0", "800"),
                line("rev", 1, "4710", "800", "0")));
        when(journalEntryRepository.findByIdIn(anyList())).thenReturn(List.of(
                entry("orig", "JNV/2026-27/0007", LocalDate.of(2026, 9, 1), JournalStatus.REVERSED),
                entry("rev", "JNV/2026-27/0008", LocalDate.of(2026, 9, 30), JournalStatus.POSTED)));

        AccountLedgerResponse ledger = service.accountLedger("4710", FROM, TO);

        assertThat(ledger.getLines()).hasSize(2);
        assertThat(ledger.getClosingBalanceInr()).isEqualByComparingTo("0");
    }

    @Test
    void accountLedger_unknownAccountIsRejected() {
        when(ledgerAccountRepository.findByCode("9999")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.accountLedger("9999", FROM, TO)).isInstanceOf(IllegalArgumentException.class);
    }
}
