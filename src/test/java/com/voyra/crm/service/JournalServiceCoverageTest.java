package com.voyra.crm.service;

import com.voyra.crm.dto.JournalEntryResponse;
import com.voyra.crm.dto.JournalLineRequest;
import com.voyra.crm.dto.JournalPostRequest;
import com.voyra.crm.entity.JournalEntry;
import com.voyra.crm.entity.JournalLine;
import com.voyra.crm.entity.LedgerAccount;
import com.voyra.crm.enums.DocumentKind;
import com.voyra.crm.enums.JournalPurpose;
import com.voyra.crm.enums.JournalSourceType;
import com.voyra.crm.enums.JournalStatus;
import com.voyra.crm.enums.LedgerAccountType;
import com.voyra.crm.models.JournalLinePosting;
import com.voyra.crm.models.JournalPosting;
import com.voyra.crm.repository.JournalEntryRepository;
import com.voyra.crm.repository.JournalLineRepository;
import com.voyra.crm.repository.LedgerAccountRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** No signed-in user here: postings are stamped with the system actor. */
@ExtendWith(MockitoExtension.class)
class JournalServiceCoverageTest {

    @Mock private JournalEntryRepository journalEntryRepository;
    @Mock private JournalLineRepository journalLineRepository;
    @Mock private LedgerAccountRepository ledgerAccountRepository;
    @Mock private DocumentNumberService documentNumberService;
    @Mock private AuditService auditService;
    @Spy private BaseCurrency baseCurrency = new BaseCurrency(null);
    @InjectMocks private JournalService service;

    private static final LocalDate DAY = LocalDate.of(2026, 9, 30);

    private void account(String code) {
        when(ledgerAccountRepository.findByCode(code)).thenReturn(Optional.of(LedgerAccount.builder().id(code).code(code)
                .name("Account " + code).accountType(LedgerAccountType.ASSET).isControl(false).build()));
    }

    private static JournalPosting posting(JournalLinePosting... lines) {
        return new JournalPosting(DAY, JournalSourceType.INVOICE, "I1", JournalPurpose.values()[0], "n", null, null, List.of(lines));
    }

    @Test
    void anEntryNeedsTwoLines() {
        assertThatThrownBy(() -> service.post(new JournalPosting(DAY, JournalSourceType.INVOICE, "I1", null, "n", null, null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("A journal entry needs at least two lines");
        assertThatThrownBy(() -> service.post(posting(JournalLinePosting.debit("1100", BigDecimal.TEN, "x"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("A journal entry needs at least two lines");
        verifyNoInteractions(ledgerAccountRepository);
    }

    @Test
    void aLineWithNeitherSideOrAnUnknownAccountIsRefused() {
        JournalLinePosting empty = new JournalLinePosting("1100", null, null, null, null, null, null, "x");
        assertThatThrownBy(() -> service.post(posting(empty, JournalLinePosting.credit("3100", BigDecimal.TEN, "x"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Journal line 1 carries neither a debit nor a credit");
        assertThatThrownBy(() -> service.post(posting(JournalLinePosting.debit("9999", BigDecimal.TEN, "x"),
                JournalLinePosting.credit("3100", BigDecimal.TEN, "x"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Unknown ledger account code: 9999");
    }

    @Test
    void aForeignLineIsPostedInBaseAtItsRateAndASystemPostingIsNotAudited() {
        account("1100");
        account("3100");
        when(documentNumberService.next(DocumentKind.JOURNAL, DAY)).thenReturn("JNV/2026-27/0009");
        JournalLinePosting usd = new JournalLinePosting("1100", "VENDOR", "V1", "USD", new BigDecimal("83"), new BigDecimal("10"), null, "usd");
        JournalLinePosting base = new JournalLinePosting("3100", null, null, null, null, null, new BigDecimal("830"), "base");

        JournalEntry entry = service.post(posting(usd, base));

        assertThat(entry.getEntryNumber()).isEqualTo("JNV/2026-27/0009");
        assertThat(entry.getFinancialYear()).isEqualTo("2026-27");
        assertThat(entry.getStatus()).isEqualTo(JournalStatus.POSTED);
        assertThat(entry.getCreatedBy()).isEqualTo(JournalService.SYSTEM_ACTOR);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<JournalLine>> lines = ArgumentCaptor.forClass(List.class);
        verify(journalLineRepository).saveAll(lines.capture());
        assertThat(lines.getValue()).extracting(JournalLine::getCurrencyCode).containsExactly("USD", "INR");
        assertThat(lines.getValue().get(0).getDebitAmountInr()).isEqualByComparingTo("830.00");
        assertThat(lines.getValue().get(1).getFxRateToInr()).isEqualByComparingTo("1");
        assertThat(lines.getValue().get(1).getDebitAmount()).isEqualByComparingTo("0");
        assertThat(lines.getValue()).extracting(JournalLine::getJournalEntryId).containsOnly(entry.getId());
        assertThat(lines.getValue()).extracting(JournalLine::getLineNo).containsExactly(1, 2);
        verifyNoInteractions(auditService);
    }

    @Test
    void aSystemPostingMayMoveMoneyThroughAControlAccount() {
        when(ledgerAccountRepository.findByCode("1200")).thenReturn(Optional.of(LedgerAccount.builder().id("1200").code("1200")
                .name("Debtors").accountType(LedgerAccountType.ASSET).isControl(true).build()));
        account("4000");
        when(documentNumberService.next(DocumentKind.JOURNAL, DAY)).thenReturn("JNV/4");

        JournalEntry entry = service.post(posting(JournalLinePosting.debitParty("1200", "CLIENT", "C1", BigDecimal.TEN, "x"),
                JournalLinePosting.credit("4000", BigDecimal.TEN, "x")));

        assertThat(entry.getSourceType()).isEqualTo(JournalSourceType.INVOICE);
        verify(journalEntryRepository).save(entry);
    }

    @Test
    void reversingNeedsAnEntryThatIsStillPosted() {
        assertThatThrownBy(() -> service.reverse("J9", "x"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Journal entry not found: J9");
        when(journalEntryRepository.findById("J1")).thenReturn(Optional.of(JournalEntry.builder().id("J1").status(JournalStatus.REVERSED).build()));
        assertThatThrownBy(() -> service.reverse("J1", "x"))
                .isInstanceOf(IllegalStateException.class).hasMessage("This entry has already been reversed");
    }

    private void reversible(JournalSourceType source) {
        when(journalEntryRepository.findById("J1")).thenReturn(Optional.of(JournalEntry.builder().id("J1").entryNumber("JNV/1")
                .sourceType(source).purpose(JournalPurpose.MANUAL_ENTRY).status(JournalStatus.POSTED).build()));
        when(journalLineRepository.findByJournalEntryIdOrderByLineNo("J1")).thenReturn(List.of(
                JournalLine.builder().lineNo(1).accountCode("1100").debitAmount(BigDecimal.TEN).creditAmount(BigDecimal.ZERO).build(),
                JournalLine.builder().lineNo(2).accountCode("3100").debitAmount(BigDecimal.ZERO).creditAmount(BigDecimal.TEN).build()));
        account("1100");
        account("3100");
        when(documentNumberService.next(eq(DocumentKind.JOURNAL), any())).thenReturn("JNV/2");
    }

    @Test
    void aReversalWithoutAReasonIsNamedAfterTheOriginalOnly() {
        reversible(JournalSourceType.INVOICE);
        assertThat(service.reverse("J1", " ").getNarration()).isEqualTo("Reversal of JNV/1");
    }

    @Test
    void aManualJournalIsReversedFromItsEndpointWithANullReason() {
        reversible(JournalSourceType.MANUAL);

        JournalEntry reversal = service.reverseManual("J1", null);

        assertThat(reversal.getNarration()).isEqualTo("Reversal of JNV/1");
        assertThat(reversal.getReversesEntryId()).isEqualTo("J1");
    }

    @Test
    void theManualReverseEndpointNeedsTheEntryAndAShortReason() {
        assertThatThrownBy(() -> service.reverseManual("J9", "x"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Journal entry not found: J9");
        when(journalEntryRepository.findById("J1")).thenReturn(Optional.of(JournalEntry.builder().id("J1").sourceType(JournalSourceType.MANUAL).build()));
        assertThatThrownBy(() -> service.reverseManual("J1", "r".repeat(151)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Keep the reason to 150 characters or fewer");
        verify(journalLineRepository, never()).findByJournalEntryIdOrderByLineNo(any());
    }

    private static JournalLineRequest line(String code, String debit, String credit, String fx) {
        JournalLineRequest l = new JournalLineRequest();
        l.setAccountCode(code);
        l.setDebitAmount(debit == null ? null : new BigDecimal(debit));
        l.setCreditAmount(credit == null ? null : new BigDecimal(credit));
        l.setFxRateToInr(fx == null ? null : new BigDecimal(fx));
        return l;
    }

    @Test
    void aManualEntryMustUseTheManualPurposeAndSaneAmounts() {
        JournalPostRequest wrongPurpose = new JournalPostRequest();
        wrongPurpose.setPurpose(JournalPurpose.values()[0] == JournalPurpose.MANUAL_ENTRY ? JournalPurpose.values()[1] : JournalPurpose.values()[0]);
        assertThatThrownBy(() -> service.postManual(wrongPurpose)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("A journal posted here is always a manual entry");

        JournalPostRequest negativeCredit = new JournalPostRequest();
        negativeCredit.setSourceType(JournalSourceType.MANUAL);
        negativeCredit.setPurpose(JournalPurpose.MANUAL_ENTRY);
        negativeCredit.setLines(List.of(line("1100", null, "-5", null)));
        assertThatThrownBy(() -> service.postManual(negativeCredit)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("A journal line cannot carry a negative amount");

        JournalPostRequest zeroRate = new JournalPostRequest();
        zeroRate.setLines(List.of(line("1100", "5", null, "0")));
        assertThatThrownBy(() -> service.postManual(zeroRate)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The exchange rate must be greater than zero");
        verifyNoInteractions(ledgerAccountRepository);
    }

    @Test
    void aManualEntryAtTheDefaultRateIsPostedAndAudited() {
        account("1100");
        account("3100");
        when(documentNumberService.next(DocumentKind.JOURNAL, DAY)).thenReturn("JNV/3");
        JournalPostRequest r = new JournalPostRequest();
        r.setEntryDate(DAY);
        r.setNarration("Opening cash");
        r.setLines(List.of(line("1100", "50", null, "1"), line("3100", null, "50", null)));

        JournalEntryResponse response = service.postManual(r);

        assertThat(response.getEntryNumber()).isEqualTo("JNV/3");
        assertThat(response.getSourceType()).isEqualTo(JournalSourceType.MANUAL);
        verify(auditService).recordCreate(com.voyra.crm.enums.AuditEntityType.JOURNAL_ENTRY, response.getId(), "JNV/3");
    }

    @Test
    void anEntryIsReadWithItsLines() {
        JournalEntry e = JournalEntry.builder().id("J1").entryNumber("JNV/1").entryDate(DAY).financialYear("2026-27")
                .status(JournalStatus.POSTED).narration("n").createdBy("AC1").build();
        when(journalEntryRepository.findById("J1")).thenReturn(Optional.of(e));
        when(journalLineRepository.findByJournalEntryIdOrderByLineNo("J1")).thenReturn(List.of(
                JournalLine.builder().id("JL1").lineNo(1).accountCode("1100").partyType("CLIENT").partyId("C1").currencyCode("INR")
                        .fxRateToInr(BigDecimal.ONE).debitAmount(BigDecimal.TEN).creditAmount(BigDecimal.ZERO)
                        .debitAmountInr(BigDecimal.TEN).creditAmountInr(BigDecimal.ZERO).narration("line").build()));

        JournalEntryResponse r = service.get("J1");

        assertThat(r.getEntryNumber()).isEqualTo("JNV/1");
        assertThat(r.getCreatedBy()).isEqualTo("AC1");
        assertThat(r.getLines()).singleElement().satisfies(l -> {
            assertThat(l.getPartyId()).isEqualTo("C1");
            assertThat(l.getDebitAmountInr()).isEqualByComparingTo("10");
            assertThat(l.getNarration()).isEqualTo("line");
        });
        assertThatThrownBy(() -> service.get("J9")).isInstanceOf(IllegalArgumentException.class).hasMessage("Journal entry not found: J9");
    }

    @Test
    void theRegisterLoadsEveryEntrysLinesInOneQueryInLineOrder() {
        assertThat(service.toResponses(List.of())).isEmpty();
        verifyNoInteractions(journalLineRepository);

        JournalEntry a = JournalEntry.builder().id("J1").build();
        JournalEntry b = JournalEntry.builder().id("J2").build();
        when(journalLineRepository.findByJournalEntryIdIn(List.of("J1", "J2"))).thenReturn(List.of(
                JournalLine.builder().id("L2").journalEntryId("J1").lineNo(2).build(),
                JournalLine.builder().id("L1").journalEntryId("J1").lineNo(1).build()));

        List<JournalEntryResponse> responses = service.toResponses(List.of(a, b));

        assertThat(responses.get(0).getLines()).extracting(l -> l.getId()).containsExactly("L1", "L2");
        assertThat(responses.get(1).getLines()).isEmpty();
    }
}
