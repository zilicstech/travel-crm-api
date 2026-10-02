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
import com.voyra.crm.enums.UserType;
import com.voyra.crm.models.JournalLinePosting;
import com.voyra.crm.models.JournalPosting;
import com.voyra.crm.repository.JournalEntryRepository;
import com.voyra.crm.repository.JournalLineRepository;
import com.voyra.crm.repository.LedgerAccountRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JournalServiceTest {

    @Mock
    private JournalEntryRepository journalEntryRepository;
    @Mock
    private JournalLineRepository journalLineRepository;
    @Mock
    private LedgerAccountRepository ledgerAccountRepository;
    @Mock
    private DocumentNumberService documentNumberService;
    @Mock
    private AuditService auditService;

    @InjectMocks
    private JournalService journalService;

    @BeforeEach
    void authenticateAsAccountant() {
        CustomUserPrincipal principal = new CustomUserPrincipal("AC1", "neha", UserType.ACCOUNTANT, "T1");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private LedgerAccount nonControlAccount(String code) {
        return LedgerAccount.builder().id(code + "-id").code(code).name("Account " + code)
                .accountType(LedgerAccountType.ASSET).isControl(false).isActive(true).build();
    }

    private LedgerAccount controlAccount(String code) {
        return LedgerAccount.builder().id(code + "-id").code(code).name("Control " + code)
                .accountType(LedgerAccountType.ASSET).isControl(true).isActive(true).build();
    }

    @Test
    void aBalancedManualEntryPostsSuccessfully() {
        when(ledgerAccountRepository.findByCode("1100")).thenReturn(Optional.of(nonControlAccount("1100")));
        when(ledgerAccountRepository.findByCode("3100")).thenReturn(Optional.of(nonControlAccount("3100")));
        when(documentNumberService.next(DocumentKind.JOURNAL, LocalDate.of(2026, 9, 30))).thenReturn("JNV/2026-27/0001");
        when(journalEntryRepository.existsById(any())).thenReturn(false);
        when(journalLineRepository.existsById(any())).thenReturn(false);

        JournalPosting posting = new JournalPosting(
                LocalDate.of(2026, 9, 30), JournalSourceType.MANUAL, null, JournalPurpose.MANUAL_ENTRY,
                "Opening cash", null, null,
                List.of(
                        JournalLinePosting.debit("1100", new BigDecimal("1000.00"), null),
                        JournalLinePosting.credit("3100", new BigDecimal("1000.00"), null)));

        JournalEntry entry = journalService.post(posting);

        assertThat(entry.getStatus()).isEqualTo(JournalStatus.POSTED);
        assertThat(entry.getEntryNumber()).isEqualTo("JNV/2026-27/0001");

        ArgumentCaptor<List<JournalLine>> linesCaptor = ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(journalLineRepository).saveAll(linesCaptor.capture());
        BigDecimal debitTotal = linesCaptor.getValue().stream().map(JournalLine::getDebitAmountInr).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal creditTotal = linesCaptor.getValue().stream().map(JournalLine::getCreditAmountInr).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(debitTotal).isEqualByComparingTo(creditTotal);
    }

    @Test
    void anUnbalancedEntryThrows() {
        when(ledgerAccountRepository.findByCode("1100")).thenReturn(Optional.of(nonControlAccount("1100")));
        when(ledgerAccountRepository.findByCode("3100")).thenReturn(Optional.of(nonControlAccount("3100")));

        JournalPosting posting = new JournalPosting(
                LocalDate.of(2026, 9, 30), JournalSourceType.MANUAL, null, JournalPurpose.MANUAL_ENTRY,
                "Mismatched", null, null,
                List.of(
                        JournalLinePosting.debit("1100", new BigDecimal("1000.00"), null),
                        JournalLinePosting.credit("3100", new BigDecimal("900.00"), null)));

        assertThatThrownBy(() -> journalService.post(posting))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not balance");
    }

    @Test
    void aManualEntryNamingAControlAccountIsRejected() {
        when(ledgerAccountRepository.findByCode("1200")).thenReturn(Optional.of(controlAccount("1200")));

        JournalPosting posting = new JournalPosting(
                LocalDate.of(2026, 9, 30), JournalSourceType.MANUAL, null, JournalPurpose.MANUAL_ENTRY,
                "Should be rejected", null, null,
                List.of(
                        JournalLinePosting.debit("1200", new BigDecimal("500.00"), null),
                        JournalLinePosting.credit("1100", new BigDecimal("500.00"), null)));

        assertThatThrownBy(() -> journalService.post(posting))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("control account");
    }

    @Test
    void aLineCarryingBothDebitAndCreditIsRejected() {
        JournalPosting posting = new JournalPosting(
                LocalDate.of(2026, 9, 30), JournalSourceType.MANUAL, null, JournalPurpose.MANUAL_ENTRY,
                "Bad line", null, null,
                List.of(
                        new JournalLinePosting("1100", null, null, "INR", BigDecimal.ONE,
                                new BigDecimal("100.00"), new BigDecimal("100.00"), null),
                        JournalLinePosting.credit("3100", new BigDecimal("100.00"), null)));

        assertThatThrownBy(() -> journalService.post(posting))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("both a debit and a credit");
    }

    @Test
    void reverseProducesANewBalancedEntryWithSwappedSidesAndMarksTheOriginalReversed() {
        when(ledgerAccountRepository.findByCode(any())).thenAnswer(inv -> Optional.of(nonControlAccount(inv.getArgument(0))));
        when(documentNumberService.next(any(), any())).thenReturn("JNV/2026-27/0002");
        when(journalEntryRepository.existsById(any())).thenReturn(false);
        when(journalLineRepository.existsById(any())).thenReturn(false);

        JournalEntry original = JournalEntry.builder().id("JE1").entryNumber("JNV/2026-27/0001")
                .entryDate(LocalDate.of(2026, 9, 30)).financialYear("2026-27")
                .sourceType(JournalSourceType.SUPPLIER_INVOICE).sourceId("SI1")
                .purpose(JournalPurpose.SUPPLIER_BILL_BOOKED).narration("Bill booked").status(JournalStatus.POSTED)
                .build();
        when(journalEntryRepository.findById("JE1")).thenReturn(Optional.of(original));
        when(journalLineRepository.findByJournalEntryIdOrderByLineNo("JE1")).thenReturn(List.of(
                JournalLine.builder().id("L1").journalEntryId("JE1").lineNo(1).accountCode("5010")
                        .currencyCode("INR").fxRateToInr(BigDecimal.ONE)
                        .debitAmount(new BigDecimal("2000.00")).creditAmount(BigDecimal.ZERO)
                        .debitAmountInr(new BigDecimal("2000.00")).creditAmountInr(BigDecimal.ZERO).build(),
                JournalLine.builder().id("L2").journalEntryId("JE1").lineNo(2).accountCode("2200")
                        .currencyCode("INR").fxRateToInr(BigDecimal.ONE)
                        .debitAmount(BigDecimal.ZERO).creditAmount(new BigDecimal("2000.00"))
                        .debitAmountInr(BigDecimal.ZERO).creditAmountInr(new BigDecimal("2000.00")).build()));

        JournalEntry reversal = journalService.reverse("JE1", "booked in error");

        assertThat(reversal.getReversesEntryId()).isEqualTo("JE1");
        assertThat(original.getStatus()).isEqualTo(JournalStatus.REVERSED);

        ArgumentCaptor<List<JournalLine>> linesCaptor = ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(journalLineRepository, org.mockito.Mockito.atLeastOnce()).saveAll(linesCaptor.capture());
        List<JournalLine> reversedLines = linesCaptor.getValue();
        assertThat(reversedLines).hasSize(2);
        BigDecimal debitTotal = reversedLines.stream().map(JournalLine::getDebitAmountInr).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal creditTotal = reversedLines.stream().map(JournalLine::getCreditAmountInr).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(debitTotal).isEqualByComparingTo(creditTotal);
    }

    @Test
    void postManualConvertsTheValidatedRequestAndPosts() {
        when(ledgerAccountRepository.findByCode(any())).thenAnswer(inv -> Optional.of(nonControlAccount(inv.getArgument(0))));
        when(documentNumberService.next(any(), any())).thenReturn("JNV/2026-27/0003");
        when(journalEntryRepository.existsById(any())).thenReturn(false);
        when(journalLineRepository.existsById(any())).thenReturn(false);

        JournalPostRequest request = new JournalPostRequest();
        request.setEntryDate(LocalDate.of(2026, 9, 30));
        request.setSourceType(JournalSourceType.MANUAL);
        request.setPurpose(JournalPurpose.MANUAL_ENTRY);
        request.setNarration("Correction");
        JournalLineRequest debitLine = new JournalLineRequest();
        debitLine.setAccountCode("1100");
        debitLine.setDebitAmount(new BigDecimal("250.00"));
        debitLine.setCreditAmount(BigDecimal.ZERO);
        JournalLineRequest creditLine = new JournalLineRequest();
        creditLine.setAccountCode("3100");
        creditLine.setDebitAmount(BigDecimal.ZERO);
        creditLine.setCreditAmount(new BigDecimal("250.00"));
        request.setLines(List.of(debitLine, creditLine));

        JournalEntryResponse response = journalService.postManual(request);

        assertThat(response.getStatus()).isEqualTo(JournalStatus.POSTED);
        assertThat(response.getEntryNumber()).isEqualTo("JNV/2026-27/0003");
    }
}
