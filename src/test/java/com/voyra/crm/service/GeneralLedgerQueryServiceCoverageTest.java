package com.voyra.crm.service;

import com.voyra.crm.dto.AccountLedgerResponse;
import com.voyra.crm.dto.JournalEntryResponse;
import com.voyra.crm.entity.JournalEntry;
import com.voyra.crm.entity.JournalLine;
import com.voyra.crm.entity.LedgerAccount;
import com.voyra.crm.enums.JournalSourceType;
import com.voyra.crm.enums.LedgerAccountType;
import com.voyra.crm.repository.JournalEntryRepository;
import com.voyra.crm.repository.JournalLineRepository;
import com.voyra.crm.repository.LedgerAccountRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GeneralLedgerQueryServiceCoverageTest {

    @Mock private LedgerAccountRepository ledgerAccountRepository;
    @Mock private JournalLineRepository journalLineRepository;
    @Mock private JournalEntryRepository journalEntryRepository;
    @Mock private JournalService journalService;
    @Spy private BaseCurrency baseCurrency = new BaseCurrency(null);
    @InjectMocks private GeneralLedgerQueryService service;

    private static final LocalDate FROM = LocalDate.of(2026, 4, 1);
    private static final LocalDate TO = LocalDate.of(2026, 9, 30);

    private void liability() {
        when(ledgerAccountRepository.findByCode("2200")).thenReturn(Optional.of(
                LedgerAccount.builder().code("2200").name("Payables").accountType(LedgerAccountType.LIABILITY).isControl(true).build()));
    }

    @Test
    void anUnknownAccountHasNoLedger() {
        assertThatThrownBy(() -> service.accountLedger("9999", FROM, TO))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Ledger account not found: 9999");
    }

    @Test
    void aQuietPeriodShowsOnlyTheOpeningBalanceAndNullSumsCountAsZero() {
        liability();
        when(journalLineRepository.sumDebitBefore("2200", FROM)).thenReturn(null);
        when(journalLineRepository.sumCreditBefore("2200", FROM)).thenReturn(new BigDecimal("400"));

        AccountLedgerResponse r = service.accountLedger("2200", FROM, TO);

        assertThat(r.getOpeningBalanceInr()).isEqualByComparingTo("400.00");
        assertThat(r.getClosingBalanceInr()).isEqualByComparingTo("400.00");
        assertThat(r.getLines()).isEmpty();
        assertThat(r.getIsControl()).isTrue();
        verifyNoInteractions(journalEntryRepository);
    }

    @Test
    void linesAreOrderedByDateThenNumberThenLineAndForeignOnesShowTheirAmount() {
        liability();
        when(journalLineRepository.sumDebitBefore("2200", FROM)).thenReturn(BigDecimal.ZERO);
        when(journalLineRepository.sumCreditBefore("2200", FROM)).thenReturn(BigDecimal.ZERO);
        LocalDate day = LocalDate.of(2026, 5, 1);
        JournalEntry b = JournalEntry.builder().id("JB").entryNumber("JNV/2").entryDate(day).narration("Entry B")
                .sourceType(JournalSourceType.SUPPLIER_INVOICE).build();
        JournalEntry a = JournalEntry.builder().id("JA").entryNumber("JNV/1").entryDate(day).narration("Entry A").build();
        when(journalLineRepository.findAccountLinesBetween("2200", FROM, TO)).thenReturn(List.of(
                JournalLine.builder().id("L3").journalEntryId("JB").lineNo(1).currencyCode(null).narration(null)
                        .debitAmountInr(null).creditAmountInr(new BigDecimal("100")).build(),
                JournalLine.builder().id("L2").journalEntryId("JA").lineNo(2).currencyCode("USD").narration(" ")
                        .debitAmount(null).creditAmount(new BigDecimal("10")).creditAmountInr(new BigDecimal("830")).debitAmountInr(BigDecimal.ZERO).build(),
                JournalLine.builder().id("L1").journalEntryId("JA").lineNo(1).currencyCode("INR").narration("Own text")
                        .debitAmountInr(new BigDecimal("30")).creditAmountInr(BigDecimal.ZERO).build()));
        when(journalEntryRepository.findByIdIn(anyList())).thenReturn(List.of(a, b));

        AccountLedgerResponse r = service.accountLedger("2200", FROM, TO);

        assertThat(r.getLines()).extracting(l -> l.getNarration()).containsExactly("Own text", "Entry A", "Entry B");
        assertThat(r.getLines()).extracting(l -> l.getRunningBalanceInr().toPlainString()).containsExactly("-30.00", "800.00", "900.00");
        assertThat(r.getLines().get(0).getForeignAmount()).isNull();
        assertThat(r.getLines().get(1).getForeignAmount()).isEqualByComparingTo("10");
        assertThat(r.getLines().get(2).getForeignAmount()).isNull();
        assertThat(r.getLines().get(2).getSourceType()).isEqualTo(JournalSourceType.SUPPLIER_INVOICE);
        assertThat(r.getTotalDebitInr()).isEqualByComparingTo("30.00");
        assertThat(r.getTotalCreditInr()).isEqualByComparingTo("930.00");
    }

    @Test
    void theRegisterFiltersByTypeOrReadsOneDocumentsEntries() {
        JournalEntry invoice = JournalEntry.builder().id("J1").sourceType(JournalSourceType.INVOICE).build();
        JournalEntry receipt = JournalEntry.builder().id("J2").sourceType(JournalSourceType.RECEIPT).build();
        when(journalEntryRepository.findByEntryDateBetweenOrderByEntryDateDescEntryNumberDesc(FROM, TO)).thenReturn(List.of(invoice, receipt));
        when(journalEntryRepository.findBySourceIdOrderByEntryDateAscEntryNumberAsc("I1")).thenReturn(List.of(invoice));
        List<JournalEntryResponse> none = List.of();
        when(journalService.toResponses(List.of(invoice))).thenReturn(none);
        when(journalService.toResponses(List.of(invoice, receipt))).thenReturn(none);

        assertThat(service.journalRegister(FROM, TO, JournalSourceType.INVOICE)).isSameAs(none);
        assertThat(service.journalRegister(FROM, TO, null)).isSameAs(none);
        assertThat(service.journalRegister(null, null, null, "I1")).isSameAs(none);

        verify(journalService, org.mockito.Mockito.times(2)).toResponses(List.of(invoice));
    }
}
