package com.voyra.crm.service;

import com.voyra.crm.entity.JournalEntry;
import com.voyra.crm.entity.JournalLine;
import com.voyra.crm.enums.JournalStatus;
import com.voyra.crm.repository.JournalEntryRepository;
import com.voyra.crm.repository.JournalLineRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LedgerIntegrityCheckServiceTest {

    @Mock private JournalEntryRepository journalEntryRepository;
    @Mock private JournalLineRepository journalLineRepository;
    @InjectMocks private LedgerIntegrityCheckService service;

    private static JournalLine line(String debit, String credit) {
        return JournalLine.builder().debitAmountInr(new BigDecimal(debit)).creditAmountInr(new BigDecimal(credit)).build();
    }

    @Test
    void balancedEntriesAreClean() {
        when(journalEntryRepository.findByStatus(JournalStatus.POSTED)).thenReturn(List.of(JournalEntry.builder().id("J1").entryNumber("JNV/1").build()));
        when(journalLineRepository.findByJournalEntryIdOrderByLineNo("J1")).thenReturn(List.of(line("100", "0"), line("0", "100")));

        LedgerIntegrityCheckService.IntegrityResult r = service.check();

        assertThat(r.entriesChecked()).isEqualTo(1);
        assertThat(r.unbalancedEntryNumbers()).isEmpty();
        assertThat(r.trialBalanceNet()).isEqualByComparingTo("0");
        assertThat(r.isClean()).isTrue();
    }

    @Test
    void anUnbalancedEntryIsNamedAndTheTrialBalanceNetShowsTheGap() {
        when(journalEntryRepository.findByStatus(JournalStatus.POSTED)).thenReturn(List.of(
                JournalEntry.builder().id("J1").entryNumber("JNV/1").build(), JournalEntry.builder().id("J2").entryNumber("JNV/2").build()));
        when(journalLineRepository.findByJournalEntryIdOrderByLineNo("J1")).thenReturn(List.of(line("100", "0"), line("0", "100")));
        when(journalLineRepository.findByJournalEntryIdOrderByLineNo("J2")).thenReturn(List.of(line("70", "0"), line("0", "50")));

        LedgerIntegrityCheckService.IntegrityResult r = service.check();

        assertThat(r.unbalancedEntryNumbers()).containsExactly("JNV/2");
        assertThat(r.trialBalanceNet()).isEqualByComparingTo("20");
        assertThat(r.isClean()).isFalse();
    }

    @Test
    void anEmptyLedgerIsClean() {
        assertThat(service.check().isClean()).isTrue();
    }

    @Test
    void aBalancedEntryListButANonZeroNetIsNotClean() {
        assertThat(new LedgerIntegrityCheckService.IntegrityResult(0, List.of(), BigDecimal.ONE).isClean()).isFalse();
        assertThat(new LedgerIntegrityCheckService.IntegrityResult(1, List.of("JNV/9"), BigDecimal.ZERO).isClean()).isFalse();
    }
}
