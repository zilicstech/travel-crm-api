package com.voyra.crm.service;

import com.voyra.crm.dto.BankTransactionResponse;
import com.voyra.crm.dto.PaymentReceiptRequest;
import com.voyra.crm.dto.PaymentReceiptResponse;
import com.voyra.crm.dto.SupplierPaymentRequest;
import com.voyra.crm.dto.SupplierPaymentResponse;
import com.voyra.crm.dto.TierTwoCandidateResponse;
import com.voyra.crm.entity.BankMatchRule;
import com.voyra.crm.entity.BankTransaction;
import com.voyra.crm.entity.Invoice;
import com.voyra.crm.entity.PaymentReceipt;
import com.voyra.crm.entity.SupplierInvoice;
import com.voyra.crm.entity.SupplierPayment;
import com.voyra.crm.enums.BankMatchField;
import com.voyra.crm.enums.BankMatchedSourceType;
import com.voyra.crm.enums.BankTransactionDirection;
import com.voyra.crm.enums.BankTransactionMatchStatus;
import com.voyra.crm.enums.InvoiceLifecycle;
import com.voyra.crm.enums.PaymentMode;
import com.voyra.crm.enums.SupplierInvoiceStatus;
import com.voyra.crm.enums.SystemAccount;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.models.JournalLinePosting;
import com.voyra.crm.models.JournalPosting;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BankMatchingServiceCoverageTest {

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
    @InjectMocks private BankMatchingService service;

    private static final LocalDate DAY = LocalDate.of(2026, 9, 20);
    private static final BigDecimal AMOUNT = new BigDecimal("1000.00");

    @BeforeEach
    void accountant() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal("AC1", "u", UserType.ACCOUNTANT, "T1"), null, List.of()));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static BankTransaction.BankTransactionBuilder txn(BankTransactionDirection direction) {
        return BankTransaction.builder().id("BT1").bankAccountId("BA1").txnDate(DAY).amount(AMOUNT).direction(direction)
                .matchStatus(BankTransactionMatchStatus.UNMATCHED);
    }

    private static Invoice invoice(String id, String number, InvoiceLifecycle status, String balanceInr, LocalDate due) {
        return Invoice.builder().id(id).invoiceNumber(number).status(status).balanceDue(new BigDecimal(balanceInr))
                .balanceDueInr(new BigDecimal(balanceInr)).dueDate(due).build();
    }

    private static SupplierInvoice bill(String id, String number, SupplierInvoiceStatus status, String balanceInr, LocalDate due) {
        return SupplierInvoice.builder().id(id).vendorId("V1").supplierInvoiceNumber(number).status(status)
                .balanceDue(new BigDecimal(balanceInr)).balanceDueInr(new BigDecimal(balanceInr)).dueDate(due).build();
    }

    @Test
    void unmatchedLinesAreListedAsTheyCameIn() {
        when(bankTransactionRepository.findByBankAccountIdAndMatchStatus("BA1", BankTransactionMatchStatus.UNMATCHED)).thenReturn(List.of(
                txn(BankTransactionDirection.CREDIT).description("NEFT").bankReference("R1").build()));

        List<BankTransactionResponse> rows = service.listUnmatched("BA1");

        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.getId()).isEqualTo("BT1");
            assertThat(r.getTxnDate()).isEqualTo(DAY);
            assertThat(r.getDescription()).isEqualTo("NEFT");
            assertThat(r.getBankReference()).isEqualTo("R1");
            assertThat(r.getAmount()).isEqualByComparingTo(AMOUNT);
            assertThat(r.getDirection()).isEqualTo(BankTransactionDirection.CREDIT);
            assertThat(r.getMatchStatus()).isEqualTo(BankTransactionMatchStatus.UNMATCHED);
        });
    }

    @Test
    void aCreditWhoseReferencedInvoiceDoesNotFitFallsBackToTheNumberInTheDescription() {
        BankTransaction wrongAmount = txn(BankTransactionDirection.CREDIT).id("BT1").bankReference(" ITI-1 ").description("NEFT ITI-2").build();
        BankTransaction closed = txn(BankTransactionDirection.CREDIT).id("BT2").bankReference("ITI-3").description(null).build();
        when(invoiceRepository.findByInvoiceNumber("ITI-1")).thenReturn(Optional.of(invoice("I1", "ITI-1", InvoiceLifecycle.ISSUED, "5", null)));
        when(invoiceRepository.findByInvoiceNumber("ITI-3")).thenReturn(Optional.of(invoice("I3", "ITI-3", InvoiceLifecycle.PAID, "1000.00", null)));
        when(invoiceRepository.findByStatusInAndBalanceDueInr(any(), any())).thenReturn(List.of(
                invoice("I0", null, InvoiceLifecycle.ISSUED, "1000.00", null),
                invoice("I9", " ", InvoiceLifecycle.ISSUED, "1000.00", null),
                invoice("I2", "iti-2", InvoiceLifecycle.PARTIALLY_PAID, "1000.00", null)));
        when(bankAccountRepository.findById("BA1")).thenReturn(Optional.empty());
        when(paymentReceiptService.record(any())).thenReturn(PaymentReceiptResponse.builder().id("PR1").build());

        assertThat(service.runTier1(List.of(wrongAmount, closed))).isEqualTo(1);

        ArgumentCaptor<PaymentReceiptRequest> receipt = ArgumentCaptor.forClass(PaymentReceiptRequest.class);
        verify(paymentReceiptService).record(receipt.capture());
        assertThat(receipt.getValue().getInvoiceId()).isEqualTo("I2");
        assertThat(receipt.getValue().getAmount()).isEqualByComparingTo("1000.00");
        assertThat(receipt.getValue().getPaymentMode()).isEqualTo(PaymentMode.BANK_TRANSFER);
        assertThat(receipt.getValue().getInstrumentRef()).isEqualTo(" ITI-1 ");
        assertThat(receipt.getValue().getBankAccountLabel()).isNull();
        assertThat(receipt.getValue().getReceivedOn()).isEqualTo(DAY);
        assertThat(wrongAmount.getMatchStatus()).isEqualTo(BankTransactionMatchStatus.TIER1_MATCHED);
        assertThat(wrongAmount.getMatchedBy()).isEqualTo("AC1");
        assertThat(closed.getMatchStatus()).isEqualTo(BankTransactionMatchStatus.UNMATCHED);
    }

    @Test
    void onlyTheReceiptsThisLineCreatedAreTaggedWithTheBankAccount() {
        BankTransaction credit = txn(BankTransactionDirection.CREDIT).bankReference(" ").description("NEFT ITI-2").build();
        when(invoiceRepository.findByStatusInAndBalanceDueInr(any(), any())).thenReturn(List.of(
                invoice("I2", "ITI-2", InvoiceLifecycle.ISSUED, "1000.00", null)));
        when(paymentReceiptService.record(any())).thenReturn(PaymentReceiptResponse.builder().id("PR1").build());
        PaymentReceipt tagged = PaymentReceipt.builder().id("P0").bankAccountId("BA9").instrumentRef(" ").receivedOn(DAY).build();
        PaymentReceipt otherRef = PaymentReceipt.builder().id("P1").instrumentRef("X").receivedOn(DAY).build();
        PaymentReceipt otherDay = PaymentReceipt.builder().id("P2").instrumentRef(" ").receivedOn(DAY.minusDays(1)).build();
        PaymentReceipt mine = PaymentReceipt.builder().id("P3").instrumentRef(" ").receivedOn(DAY).build();
        when(paymentReceiptRepository.findByInvoiceIdOrderByReceivedOnAscCreatedAtAsc("I2")).thenReturn(List.of(tagged, otherRef, otherDay, mine));

        service.runTier1(List.of(credit));

        verify(invoiceRepository, never()).findByInvoiceNumber(any());
        assertThat(mine.getBankAccountId()).isEqualTo("BA1");
        assertThat(tagged.getBankAccountId()).isEqualTo("BA9");
        assertThat(otherRef.getBankAccountId()).isNull();
        assertThat(otherDay.getBankAccountId()).isNull();
        verify(paymentReceiptRepository).save(mine);
    }

    @Test
    void aDebitIsMatchedToABillByItsReferenceOrByTheNumberInTheReference() {
        BankTransaction byRef = txn(BankTransactionDirection.DEBIT).id("BT1").bankReference("SB-1").build();
        BankTransaction byNumber = txn(BankTransactionDirection.DEBIT).id("BT2").bankReference("UTR SB-2").description(null).build();
        BankTransaction none = txn(BankTransactionDirection.DEBIT).id("BT3").bankReference(null).description("FEE").build();
        when(supplierInvoiceRepository.findBySupplierInvoiceNumber("SB-1")).thenReturn(Optional.of(bill("S1", "SB-1", SupplierInvoiceStatus.APPROVED, "1000.00", null)));
        when(supplierInvoiceRepository.findBySupplierInvoiceNumber("UTR SB-2")).thenReturn(Optional.empty());
        when(supplierInvoiceRepository.findByStatusInAndBalanceDueInr(any(), any())).thenReturn(List.of(
                bill("S0", null, SupplierInvoiceStatus.APPROVED, "1000.00", null),
                bill("S2", "SB-2", SupplierInvoiceStatus.PARTIALLY_PAID, "1000.00", null)));
        when(bankAccountRepository.findById("BA1")).thenReturn(Optional.of(com.voyra.crm.entity.BankAccount.builder().id("BA1").accountName("HDFC").build()));
        when(supplierPaymentService.pay(any())).thenReturn(SupplierPaymentResponse.builder().id("SP1").build(),
                SupplierPaymentResponse.builder().id("SP2").build());
        SupplierPayment stored = SupplierPayment.builder().id("SP1").build();
        when(supplierPaymentRepository.findById("SP1")).thenReturn(Optional.of(stored));
        when(supplierPaymentRepository.findById("SP2")).thenReturn(Optional.empty());

        assertThat(service.runTier1(List.of(byRef, byNumber, none))).isEqualTo(2);

        ArgumentCaptor<SupplierPaymentRequest> pay = ArgumentCaptor.forClass(SupplierPaymentRequest.class);
        verify(supplierPaymentService, org.mockito.Mockito.times(2)).pay(pay.capture());
        assertThat(pay.getAllValues()).extracting(SupplierPaymentRequest::getSupplierInvoiceId).containsExactly("S1", "S2");
        assertThat(pay.getAllValues().get(0).getBankAccountLabel()).isEqualTo("HDFC");
        assertThat(pay.getAllValues().get(0).getVendorId()).isEqualTo("V1");
        assertThat(pay.getAllValues().get(0).getPaidOn()).isEqualTo(DAY);
        assertThat(stored.getBankAccountId()).isEqualTo("BA1");
        assertThat(byNumber.getMatchedSourceType()).isEqualTo(BankMatchedSourceType.SUPPLIER_INVOICE);
        assertThat(byNumber.getMatchedSourceId()).isEqualTo("SP2");
        assertThat(none.getMatchStatus()).isEqualTo(BankTransactionMatchStatus.UNMATCHED);
    }

    @Test
    void linesWithoutAReferenceMatchByTheNumberWhereverItAppears() {
        BankTransaction noRef = txn(BankTransactionDirection.CREDIT).id("BT1").bankReference(null).description("NEFT").build();
        BankTransaction refOnly = txn(BankTransactionDirection.CREDIT).id("BT2").bankReference("UTR ITI-5").description(null).build();
        BankTransaction blankRefDebit = txn(BankTransactionDirection.DEBIT).id("BT3").bankReference(" ").description("PAY SB-7").build();
        when(invoiceRepository.findByInvoiceNumber("UTR ITI-5")).thenReturn(Optional.empty());
        when(invoiceRepository.findByStatusInAndBalanceDueInr(any(), any())).thenReturn(List.of(invoice("I5", "ITI-5", InvoiceLifecycle.ISSUED, "1000.00", null)));
        when(supplierInvoiceRepository.findByStatusInAndBalanceDueInr(any(), any())).thenReturn(List.of(bill("S7", "SB-7", SupplierInvoiceStatus.APPROVED, "1000.00", null)));
        when(paymentReceiptService.record(any())).thenReturn(PaymentReceiptResponse.builder().id("PR5").build());
        when(supplierPaymentService.pay(any())).thenReturn(SupplierPaymentResponse.builder().id("SP7").build());

        assertThat(service.runTier1(List.of(noRef, refOnly, blankRefDebit))).isEqualTo(2);

        assertThat(noRef.getMatchStatus()).isEqualTo(BankTransactionMatchStatus.UNMATCHED);
        assertThat(refOnly.getMatchedSourceId()).isEqualTo("PR5");
        assertThat(blankRefDebit.getMatchedSourceId()).isEqualTo("SP7");
        verify(supplierInvoiceRepository, never()).findBySupplierInvoiceNumber(any());
    }

    @Test
    void aReferencedBillThatIsPaidOrForAnotherAmountIsNotAnExactMatch() {
        BankTransaction paidBill = txn(BankTransactionDirection.DEBIT).id("BT1").bankReference("SB-1").build();
        BankTransaction otherAmount = txn(BankTransactionDirection.DEBIT).id("BT2").bankReference("SB-2").build();
        when(supplierInvoiceRepository.findBySupplierInvoiceNumber("SB-1")).thenReturn(Optional.of(bill("S1", "SB-1", SupplierInvoiceStatus.PAID, "1000.00", null)));
        when(supplierInvoiceRepository.findBySupplierInvoiceNumber("SB-2")).thenReturn(Optional.of(bill("S2", "SB-2", SupplierInvoiceStatus.APPROVED, "9", null)));
        when(supplierInvoiceRepository.findByStatusInAndBalanceDueInr(any(), any())).thenReturn(List.of());

        assertThat(service.runTier1(List.of(paidBill, otherAmount))).isZero();
        verifyNoInteractions(supplierPaymentService);
    }

    @Test
    void tierTwoScoresCandidatesByDaysFromDueAndOffersTheFirstSuggestOnlyRule() {
        BankTransaction credit = txn(BankTransactionDirection.CREDIT).id("BT1").description("Card settlement").bankReference(null).build();
        BankTransaction debit = txn(BankTransactionDirection.DEBIT).id("BT2").description(null).bankReference("CHG-AMC").build();
        when(bankTransactionRepository.findByBankAccountIdAndMatchStatus("BA1", BankTransactionMatchStatus.UNMATCHED)).thenReturn(List.of(credit, debit));
        when(invoiceRepository.findByStatusInAndBalanceDueInr(any(), any())).thenReturn(List.of(
                invoice("I1", "ITI-1", InvoiceLifecycle.ISSUED, "1000.00", null),
                invoice("I2", "ITI-2", InvoiceLifecycle.ISSUED, "1000.00", DAY.plusDays(1)),
                invoice("I3", "ITI-3", InvoiceLifecycle.ISSUED, "1000.00", DAY.minusDays(3))));
        when(supplierInvoiceRepository.findByStatusInAndBalanceDueInr(any(), any())).thenReturn(List.of(
                bill("S1", "SB-1", SupplierInvoiceStatus.APPROVED, "1000.00", DAY.minusDays(2)),
                bill("S2", "SB-2", SupplierInvoiceStatus.APPROVED, "1000.00", DAY.plusDays(5))));
        BankMatchRule noFit = BankMatchRule.builder().id("R0").name("None").matchField(BankMatchField.DESCRIPTION).pattern("ZZZ")
                .targetAccountCode("5600").autoPost(false).build();
        BankMatchRule autoPost = BankMatchRule.builder().id("R1").name("Auto").matchField(BankMatchField.DESCRIPTION).pattern("card")
                .targetAccountCode("5620").autoPost(true).build();
        BankMatchRule byReference = BankMatchRule.builder().id("R2").name("AMC").matchField(BankMatchField.BANK_REFERENCE).pattern("amc")
                .targetAccountCode("5630").autoPost(false).build();
        BankMatchRule byDescription = BankMatchRule.builder().id("R3").name("Card").matchField(BankMatchField.DESCRIPTION).pattern("CARD")
                .targetAccountCode("5640").autoPost(null).build();
        BankMatchRule later = BankMatchRule.builder().id("R4").name("Later").matchField(BankMatchField.DESCRIPTION).pattern("card")
                .targetAccountCode("5650").autoPost(false).build();
        when(bankMatchRuleRepository.findByIsActiveTrueOrderByPriorityAsc()).thenReturn(List.of(noFit, autoPost, byReference, byDescription, later));

        List<TierTwoCandidateResponse> candidates = service.listTier2Candidates("BA1");

        assertThat(candidates).extracting(TierTwoCandidateResponse::getSourceId, c -> c.getScore().toPlainString())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("I1", "50.00"),
                        org.assertj.core.groups.Tuple.tuple("I2", "75.00"),
                        org.assertj.core.groups.Tuple.tuple("R3", "100"),
                        org.assertj.core.groups.Tuple.tuple("S1", "50.00"),
                        org.assertj.core.groups.Tuple.tuple("R2", "100"));
        assertThat(candidates.get(2).getSourceLabel()).isEqualTo("Rule: Card → 5640");
        assertThat(candidates.get(3).getSourceType()).isEqualTo(BankMatchedSourceType.SUPPLIER_INVOICE);
        assertThat(candidates.get(3).getSourceLabel()).isEqualTo("SB-1");
        verifyNoInteractions(paymentReceiptService, supplierPaymentService, journalService);
    }

    @Test
    void confirmingNeedsAnOpenLineAndAKnownSource() {
        assertThatThrownBy(() -> service.confirmMatch("BT9", BankMatchedSourceType.INVOICE, "I1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Bank transaction not found: BT9");
        BankTransaction done = txn(BankTransactionDirection.CREDIT).matchStatus(BankTransactionMatchStatus.CONFIRMED).build();
        when(bankTransactionRepository.findById("BT1")).thenReturn(Optional.of(done));
        assertThatThrownBy(() -> service.confirmMatch("BT1", BankMatchedSourceType.INVOICE, "I1"))
                .isInstanceOf(IllegalStateException.class).hasMessage("This transaction is already CONFIRMED");

        done.setMatchStatus(BankTransactionMatchStatus.UNMATCHED);
        assertThatThrownBy(() -> service.confirmMatch("BT1", BankMatchedSourceType.INVOICE, "I9"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Invoice not found: I9");
        assertThatThrownBy(() -> service.confirmMatch("BT1", BankMatchedSourceType.SUPPLIER_INVOICE, "S9"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Supplier bill not found: S9");
        assertThatThrownBy(() -> service.confirmMatch("BT1", BankMatchedSourceType.BANK_MATCH_RULE, "R9"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Rule not found: R9");
        assertThatThrownBy(() -> service.confirmMatch("BT1", null, "X"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Cannot confirm against source type null");
        verifyNoInteractions(paymentReceiptService, supplierPaymentService, journalService);
    }

    @Test
    void confirmingABillPaysItThroughTheSupplierPaymentService() {
        BankTransaction debit = txn(BankTransactionDirection.DEBIT).matchStatus(BankTransactionMatchStatus.TIER2_CANDIDATE).build();
        when(bankTransactionRepository.findById("BT1")).thenReturn(Optional.of(debit));
        when(supplierInvoiceRepository.findById("S1")).thenReturn(Optional.of(bill("S1", "SB-1", SupplierInvoiceStatus.APPROVED, "1000.00", null)));
        when(supplierPaymentService.pay(any())).thenReturn(SupplierPaymentResponse.builder().id("SP1").build());

        service.confirmMatch("BT1", BankMatchedSourceType.SUPPLIER_INVOICE, "S1");

        assertThat(debit.getMatchStatus()).isEqualTo(BankTransactionMatchStatus.CONFIRMED);
        assertThat(debit.getMatchedSourceId()).isEqualTo("SP1");
    }

    @Test
    void autoPostRulesSkipLinesTheyDoNotFitAndPostACreditToTheBank() {
        BankTransaction noText = txn(BankTransactionDirection.CREDIT).id("BT1").description(null).build();
        BankTransaction suggested = txn(BankTransactionDirection.CREDIT).id("BT2").description("Interest suggested").build();
        BankTransaction interest = txn(BankTransactionDirection.CREDIT).id("BT3").description("SB interest").build();
        when(bankTransactionRepository.findByBankAccountIdAndMatchStatus("BA1", BankTransactionMatchStatus.UNMATCHED))
                .thenReturn(List.of(noText, suggested, interest));
        BankMatchRule suggest = BankMatchRule.builder().id("R1").name("Suggest").matchField(BankMatchField.DESCRIPTION).pattern("SUGGESTED")
                .targetAccountCode("4800").autoPost(false).build();
        BankMatchRule byRef = BankMatchRule.builder().id("R0").name("Ref").matchField(BankMatchField.BANK_REFERENCE).pattern("INT")
                .targetAccountCode("4800").autoPost(true).build();
        BankMatchRule post = BankMatchRule.builder().id("R2").name("Interest").matchField(BankMatchField.DESCRIPTION).pattern("interest")
                .targetAccountCode("4800").autoPost(true).build();
        when(bankMatchRuleRepository.findByIsActiveTrueOrderByPriorityAsc()).thenReturn(List.of(byRef, suggest, post));

        assertThat(service.applyMatchRules("BA1")).isEqualTo(1);

        ArgumentCaptor<JournalPosting> journal = ArgumentCaptor.forClass(JournalPosting.class);
        verify(journalService).post(journal.capture());
        String narration = "Bank charge categorised by rule 'Interest': SB interest";
        assertThat(journal.getValue().sourceId()).isEqualTo("BT3");
        assertThat(journal.getValue().lines()).containsExactly(
                JournalLinePosting.debit(SystemAccount.BANK_ACCOUNTS.code(), AMOUNT, narration),
                JournalLinePosting.credit("4800", AMOUNT, narration));
        assertThat(interest.getMatchStatus()).isEqualTo(BankTransactionMatchStatus.CATEGORIZED);
        assertThat(interest.getMatchedBy()).isEqualTo("AC1");
        assertThat(suggested.getMatchStatus()).isEqualTo(BankTransactionMatchStatus.UNMATCHED);
        assertThat(noText.getMatchStatus()).isEqualTo(BankTransactionMatchStatus.UNMATCHED);
    }
}
