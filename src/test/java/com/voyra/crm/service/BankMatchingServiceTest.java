package com.voyra.crm.service;

import com.voyra.crm.dto.PaymentReceiptRequest;
import com.voyra.crm.dto.PaymentReceiptResponse;
import com.voyra.crm.dto.SupplierPaymentRequest;
import com.voyra.crm.dto.SupplierPaymentResponse;
import com.voyra.crm.dto.TierTwoCandidateResponse;
import com.voyra.crm.entity.BankAccount;
import com.voyra.crm.entity.BankTransaction;
import com.voyra.crm.entity.Invoice;
import com.voyra.crm.entity.PaymentReceipt;
import com.voyra.crm.entity.BankMatchRule;
import com.voyra.crm.entity.SupplierInvoice;
import com.voyra.crm.entity.SupplierPayment;
import com.voyra.crm.enums.BankMatchedSourceType;
import com.voyra.crm.enums.BankTransactionDirection;
import com.voyra.crm.enums.BankTransactionMatchStatus;
import com.voyra.crm.enums.BankMatchField;
import com.voyra.crm.enums.InvoiceLifecycle;
import com.voyra.crm.enums.JournalPurpose;
import com.voyra.crm.enums.SupplierInvoiceStatus;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.repository.BankAccountRepository;
import com.voyra.crm.repository.BankMatchRuleRepository;
import com.voyra.crm.repository.BankTransactionRepository;
import com.voyra.crm.repository.InvoiceRepository;
import com.voyra.crm.repository.PaymentReceiptRepository;
import com.voyra.crm.repository.SupplierInvoiceRepository;
import com.voyra.crm.repository.SupplierPaymentRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Rule 4.3.2 is the entire point of this test class: matching never posts directly. Tier 1 and
 * confirmed tier-2 candidates must call through {@code PaymentReceiptService#record}/{@code
 * SupplierPaymentService#pay} - the exact path a human uses - and a bare tier-2 candidate (never
 * confirmed) must call neither.
 */
@ExtendWith(MockitoExtension.class)
class BankMatchingServiceTest {

    @Mock private BankTransactionRepository bankTransactionRepository;
    @Mock private BankAccountRepository bankAccountRepository;
    @Mock private BankMatchRuleRepository bankMatchRuleRepository;
    @Mock private InvoiceRepository invoiceRepository;
    @Mock private SupplierInvoiceRepository supplierInvoiceRepository;
    @Mock private PaymentReceiptRepository paymentReceiptRepository;
    @Mock private SupplierPaymentRepository supplierPaymentRepository;
    @Mock private PaymentReceiptService paymentReceiptService;
    @Mock private SupplierPaymentService supplierPaymentService;
    @Mock private JournalService journalService;

    @InjectMocks
    private BankMatchingService bankMatchingService;

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

    private BankTransaction creditTxn() {
        return BankTransaction.builder()
                .id("BT1").bankAccountId("BA1").txnDate(LocalDate.of(2026, 9, 20))
                .description("NEFT FROM CLIENT").bankReference("ITI-0001")
                .amount(new BigDecimal("42000.00")).direction(BankTransactionDirection.CREDIT)
                .matchStatus(BankTransactionMatchStatus.UNMATCHED)
                .build();
    }

    @Test
    void tier1ExactMatchPostsThroughTheRealReceiptServiceAndMarksMatched() {
        BankTransaction txn = creditTxn();
        Invoice invoice = Invoice.builder().id("INV1").invoiceNumber("ITI-0001")
                .status(InvoiceLifecycle.ISSUED).balanceDue(new BigDecimal("42000.00"))
                .balanceDueInr(new BigDecimal("42000.00")).build();
        when(invoiceRepository.findByInvoiceNumber("ITI-0001")).thenReturn(Optional.of(invoice));
        when(bankAccountRepository.findById("BA1")).thenReturn(Optional.of(
                BankAccount.builder().id("BA1").accountName("HDFC Current").build()));
        when(paymentReceiptService.record(any(PaymentReceiptRequest.class)))
                .thenReturn(PaymentReceiptResponse.builder().id("PR1").build());
        when(paymentReceiptRepository.findById("PR1")).thenReturn(Optional.of(PaymentReceipt.builder().id("PR1").build()));

        int matched = bankMatchingService.runTier1(List.of(txn));

        assertThat(matched).isEqualTo(1);
        verify(paymentReceiptService).record(any(PaymentReceiptRequest.class));
        assertThat(txn.getMatchStatus()).isEqualTo(BankTransactionMatchStatus.TIER1_MATCHED);
        assertThat(txn.getMatchedSourceId()).isEqualTo("PR1");
    }

    @Test
    void tier1WithNoExactMatchLeavesTheTransactionUnmatchedAndNeverPosts() {
        BankTransaction txn = creditTxn();
        when(invoiceRepository.findByInvoiceNumber("ITI-0001")).thenReturn(Optional.empty());
        when(invoiceRepository.findByStatusInAndBalanceDueInr(any(), any())).thenReturn(List.of());

        int matched = bankMatchingService.runTier1(List.of(txn));

        assertThat(matched).isZero();
        assertThat(txn.getMatchStatus()).isEqualTo(BankTransactionMatchStatus.UNMATCHED);
        verify(paymentReceiptService, never()).record(any());
    }

    @Test
    void tier2CandidatesAreSurfacedButNeverAutoPosted() {
        when(bankTransactionRepository.findByBankAccountIdAndMatchStatus("BA1", BankTransactionMatchStatus.UNMATCHED))
                .thenReturn(List.of(creditTxn()));
        Invoice candidate = Invoice.builder().id("INV2").invoiceNumber("ITI-9999")
                .status(InvoiceLifecycle.ISSUED).dueDate(LocalDate.of(2026, 9, 19)).build();
        when(invoiceRepository.findByStatusInAndBalanceDueInr(any(), any())).thenReturn(List.of(candidate));

        List<TierTwoCandidateResponse> candidates = bankMatchingService.listTier2Candidates("BA1");

        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).getSourceType()).isEqualTo(BankMatchedSourceType.INVOICE);
        verify(paymentReceiptService, never()).record(any());
        verify(supplierPaymentService, never()).pay(any());
    }

    @Test
    void confirmingATier2CandidateCallsTheRealPostingService() {
        BankTransaction txn = creditTxn();
        txn.setMatchStatus(BankTransactionMatchStatus.TIER2_CANDIDATE);
        when(bankTransactionRepository.findById("BT1")).thenReturn(Optional.of(txn));
        Invoice invoice = Invoice.builder().id("INV2").invoiceNumber("ITI-9999")
                .status(InvoiceLifecycle.ISSUED).balanceDue(new BigDecimal("42000.00")).build();
        when(invoiceRepository.findById("INV2")).thenReturn(Optional.of(invoice));
        when(bankAccountRepository.findById("BA1")).thenReturn(Optional.of(
                BankAccount.builder().id("BA1").accountName("HDFC Current").build()));
        when(paymentReceiptService.record(any(PaymentReceiptRequest.class)))
                .thenReturn(PaymentReceiptResponse.builder().id("PR2").build());
        when(paymentReceiptRepository.findById("PR2")).thenReturn(Optional.of(PaymentReceipt.builder().id("PR2").build()));

        bankMatchingService.confirmMatch("BT1", BankMatchedSourceType.INVOICE, "INV2");

        verify(paymentReceiptService).record(any(PaymentReceiptRequest.class));
        assertThat(txn.getMatchStatus()).isEqualTo(BankTransactionMatchStatus.CONFIRMED);
    }

    @Test
    void tier1DebitMatchPostsThroughTheRealSupplierPaymentService() {
        BankTransaction txn = BankTransaction.builder()
                .id("BT2").bankAccountId("BA1").txnDate(LocalDate.of(2026, 9, 20))
                .description("PAYMENT TO VENDOR").bankReference("SB-0001")
                .amount(new BigDecimal("13020.00")).direction(BankTransactionDirection.DEBIT)
                .matchStatus(BankTransactionMatchStatus.UNMATCHED)
                .build();
        SupplierInvoice bill = SupplierInvoice.builder().id("SI1").supplierInvoiceNumber("SB-0001")
                .vendorId("V1").status(SupplierInvoiceStatus.APPROVED)
                .balanceDue(new BigDecimal("13020.00")).balanceDueInr(new BigDecimal("13020.00")).build();
        when(supplierInvoiceRepository.findBySupplierInvoiceNumber("SB-0001")).thenReturn(Optional.of(bill));
        when(bankAccountRepository.findById("BA1")).thenReturn(Optional.of(
                BankAccount.builder().id("BA1").accountName("HDFC Current").build()));
        when(supplierPaymentService.pay(any(SupplierPaymentRequest.class)))
                .thenReturn(SupplierPaymentResponse.builder().id("SP1").build());
        when(supplierPaymentRepository.findById("SP1")).thenReturn(Optional.of(SupplierPayment.builder().id("SP1").build()));

        int matched = bankMatchingService.runTier1(List.of(txn));

        assertThat(matched).isEqualTo(1);
        verify(supplierPaymentService).pay(any(SupplierPaymentRequest.class));
        assertThat(txn.getMatchStatus()).isEqualTo(BankTransactionMatchStatus.TIER1_MATCHED);
    }

    @Test
    void anAutoPostRuleAppliedToAnUnmatchedTransactionPostsRule16Directly() {
        BankTransaction txn = BankTransaction.builder()
                .id("BT3").bankAccountId("BA1").txnDate(LocalDate.of(2026, 9, 20))
                .description("WIRE TRANSFER FEE").amount(new BigDecimal("500.00"))
                .direction(BankTransactionDirection.DEBIT).matchStatus(BankTransactionMatchStatus.UNMATCHED)
                .build();
        BankMatchRule rule = BankMatchRule.builder().id("RULE1").name("Wire fee")
                .matchField(BankMatchField.DESCRIPTION).pattern("WIRE TRANSFER FEE")
                .targetAccountCode("5620").autoPost(true).priority(10).isActive(true).build();
        when(bankTransactionRepository.findByBankAccountIdAndMatchStatus("BA1", BankTransactionMatchStatus.UNMATCHED))
                .thenReturn(List.of(txn));
        when(bankMatchRuleRepository.findByIsActiveTrueOrderByPriorityAsc()).thenReturn(List.of(rule));

        int applied = bankMatchingService.applyMatchRules("BA1");

        assertThat(applied).isEqualTo(1);
        verify(journalService).post(argThat(p -> p.purpose() == JournalPurpose.BANK_CHARGE_CATEGORIZED));
        assertThat(txn.getMatchStatus()).isEqualTo(BankTransactionMatchStatus.CATEGORIZED);
    }

    @Test
    void aNonAutoPostRuleOnlyPreFillsTheCategoryAndPostsNothing() {
        BankTransaction txn = BankTransaction.builder()
                .id("BT4").bankAccountId("BA1").txnDate(LocalDate.of(2026, 9, 20))
                .description("ANNUAL MAINTENANCE CHARGE").amount(new BigDecimal("200.00"))
                .direction(BankTransactionDirection.DEBIT).matchStatus(BankTransactionMatchStatus.UNMATCHED)
                .build();
        BankMatchRule rule = BankMatchRule.builder().id("RULE2").name("AMC")
                .matchField(BankMatchField.DESCRIPTION).pattern("ANNUAL MAINTENANCE")
                .targetAccountCode("5620").autoPost(false).priority(10).isActive(true).build();
        when(bankTransactionRepository.findByBankAccountIdAndMatchStatus("BA1", BankTransactionMatchStatus.UNMATCHED))
                .thenReturn(List.of(txn));
        when(bankMatchRuleRepository.findByIsActiveTrueOrderByPriorityAsc()).thenReturn(List.of(rule));

        int applied = bankMatchingService.applyMatchRules("BA1");

        assertThat(applied).isEqualTo(1);
        verify(journalService, never()).post(any());
        assertThat(txn.getMatchStatus()).isEqualTo(BankTransactionMatchStatus.CATEGORIZED);
        assertThat(txn.getMatchedSourceId()).isEqualTo("RULE2");
    }
}
