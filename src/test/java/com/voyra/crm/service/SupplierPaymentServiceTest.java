package com.voyra.crm.service;

import com.voyra.crm.dto.SupplierAdvanceApplyRequest;
import com.voyra.crm.dto.SupplierPaymentRequest;
import com.voyra.crm.dto.SupplierPaymentResponse;
import com.voyra.crm.entity.JournalEntry;
import com.voyra.crm.entity.SupplierInvoice;
import com.voyra.crm.entity.SupplierPayment;
import com.voyra.crm.entity.Vendor;
import com.voyra.crm.enums.DocumentKind;
import com.voyra.crm.enums.JournalPurpose;
import com.voyra.crm.enums.JournalSourceType;
import com.voyra.crm.enums.PaymentMode;
import com.voyra.crm.enums.SupplierInvoiceStatus;
import com.voyra.crm.enums.SupplierPaymentDirection;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.enums.FxRateSource;
import com.voyra.crm.models.JournalPosting;
import com.voyra.crm.models.ResolvedExchangeRate;
import com.voyra.crm.repository.JournalEntryRepository;
import com.voyra.crm.repository.SupplierInvoiceRepository;
import com.voyra.crm.repository.SupplierPaymentRepository;
import com.voyra.crm.repository.VendorRepository;
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
import static org.mockito.Mockito.when;

/**
 * Journal-content coverage for the three posting shapes {@code SupplierPaymentService} writes -
 * rows 9/10/11 of the posting-rule table, ACCOUNTING_EXPANSION_ARCHITECTURE.md §1.5. Each test
 * captures the actual {@link JournalPosting} handed to the mocked {@link JournalService} and
 * asserts purpose/accounts/amounts exactly, not just "a journal was posted" - same style as
 * {@code InvoiceDocumentServiceTest}'s GL tests and {@code PaymentReceiptServiceTest}'s.
 */
@ExtendWith(MockitoExtension.class)
class SupplierPaymentServiceTest {

    @Mock
    private SupplierPaymentRepository supplierPaymentRepository;
    @Mock
    private SupplierInvoiceRepository supplierInvoiceRepository;
    @Mock
    private VendorRepository vendorRepository;
    @Mock
    private com.voyra.crm.repository.TenantRepository tenantRepository;
    @Mock
    private DocumentNumberService documentNumberService;
    @Mock
    private SupplierLedgerService supplierLedgerService;
    @Mock
    private AuditService auditService;
    @Mock
    private JournalService journalService;
    @Mock
    private JournalEntryRepository journalEntryRepository;
    @Mock
    private ExchangeRateProvider exchangeRateProvider;
    @Mock
    private com.voyra.crm.repository.BookingRepository bookingRepository;
    @Mock
    private LedgerAccountResolver ledgerAccountResolver;

    @org.mockito.Spy

    private com.voyra.crm.service.BaseCurrency baseCurrency = new com.voyra.crm.service.BaseCurrency(null);


    @InjectMocks
    private SupplierPaymentService supplierPaymentService;

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

    private Vendor vendor() {
        return Vendor.builder().id("V1").name("Novotel Goa").stateCode("30").paymentTermsDays(15).build();
    }

    private SupplierInvoice approvedBill(BigDecimal grandTotal) {
        return SupplierInvoice.builder().id("SI1").vendorId("V1").vendorName("Novotel Goa")
                .status(SupplierInvoiceStatus.APPROVED).currencyCode("INR").fxRateToInr(BigDecimal.ONE)
                .grandTotal(grandTotal).amountPaid(BigDecimal.ZERO).creditNoteTotal(BigDecimal.ZERO)
                .balanceDue(grandTotal).balanceDueInr(grandTotal).build();
    }

    private SupplierPaymentRequest request(String supplierInvoiceId, BigDecimal amount) {
        SupplierPaymentRequest r = new SupplierPaymentRequest();
        r.setVendorId("V1");
        r.setSupplierInvoiceId(supplierInvoiceId);
        r.setAmount(amount);
        r.setPaymentMode(PaymentMode.BANK_TRANSFER);
        r.setPaidOn(LocalDate.of(2026, 9, 19));
        return r;
    }

    private SupplierInvoice foreignBill(BigDecimal grandTotal, BigDecimal billRate) {
        return SupplierInvoice.builder().id("SI1").vendorId("V1").vendorName("Novotel Goa")
                .status(SupplierInvoiceStatus.APPROVED).currencyCode("USD").fxRateToInr(billRate)
                .grandTotal(grandTotal).amountPaid(BigDecimal.ZERO).creditNoteTotal(BigDecimal.ZERO)
                .balanceDue(grandTotal).balanceDueInr(grandTotal.multiply(billRate)).build();
    }

    @Test
    void payingAgainstABillPostsTheSettledJournal() {
        when(vendorRepository.findById("V1")).thenReturn(Optional.of(vendor()));
        SupplierInvoice invoice = approvedBill(new BigDecimal("1000.00"));
        when(supplierInvoiceRepository.findByIdForUpdate("SI1")).thenReturn(Optional.of(invoice));
        when(supplierPaymentRepository.existsById(any())).thenReturn(false);
        when(documentNumberService.next(DocumentKind.PAYMENT_VOUCHER, LocalDate.of(2026, 9, 19)))
                .thenReturn("PAY/2026-27/0001");

        supplierPaymentService.pay(request("SI1", new BigDecimal("400.00")));

        ArgumentCaptor<JournalPosting> captor = ArgumentCaptor.forClass(JournalPosting.class);
        verify(journalService).post(captor.capture());
        JournalPosting posting = captor.getValue();

        assertThat(posting.purpose()).isEqualTo(JournalPurpose.SUPPLIER_PAYMENT_SETTLED);
        assertThat(posting.lines()).hasSize(2); // no fx variance - bill rate and payment rate both 1
        assertThat(posting.lines().get(0).accountCode()).isEqualTo("2200"); // ACCOUNTS_PAYABLE
        assertThat(posting.lines().get(0).partyType()).isEqualTo("VENDOR");
        assertThat(posting.lines().get(0).partyId()).isEqualTo("V1");
        assertThat(posting.lines().get(0).debitAmount()).isEqualByComparingTo("400.00");
        assertThat(posting.lines().get(1).accountCode()).isEqualTo("1110"); // BANK_ACCOUNTS
        assertThat(posting.lines().get(1).creditAmount()).isEqualByComparingTo("400.00");
    }

    @Test
    void payingMoreThanTheBalanceDueIsRejectedAndPostsNothing() {
        when(vendorRepository.findById("V1")).thenReturn(Optional.of(vendor()));
        when(supplierInvoiceRepository.findByIdForUpdate("SI1")).thenReturn(Optional.of(approvedBill(new BigDecimal("1000.00"))));

        assertThatThrownBy(() -> supplierPaymentService.pay(request("SI1", new BigDecimal("1000.01"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exceeds the bill's balance due");
        verify(journalService, never()).post(any());
        verify(supplierPaymentRepository, never()).save(any());
    }

    @Test
    void theOverBalanceMessageShowsFormattedAmounts() {
        when(vendorRepository.findById("V1")).thenReturn(Optional.of(vendor()));
        when(supplierInvoiceRepository.findByIdForUpdate("SI1")).thenReturn(Optional.of(approvedBill(new BigDecimal("10000.00"))));

        assertThatThrownBy(() -> supplierPaymentService.pay(request("SI1", new BigDecimal("15000"))))
                .hasMessageContaining("payment of ₹15,000 exceeds the bill's balance due of ₹10,000.");
    }

    @Test
    void payingExactlyTheBalanceDueIsAllowed() {
        when(vendorRepository.findById("V1")).thenReturn(Optional.of(vendor()));
        when(supplierInvoiceRepository.findByIdForUpdate("SI1")).thenReturn(Optional.of(approvedBill(new BigDecimal("1000.00"))));
        when(supplierPaymentRepository.existsById(any())).thenReturn(false);
        when(documentNumberService.next(DocumentKind.PAYMENT_VOUCHER, LocalDate.of(2026, 9, 19))).thenReturn("PAY/2026-27/0001");

        supplierPaymentService.pay(request("SI1", new BigDecimal("1000.00")));

        verify(journalService).post(any());
    }

    @Test
    void payingAnAdvancePostsTheAdvancePaidJournal() {
        when(vendorRepository.findById("V1")).thenReturn(Optional.of(vendor()));
        when(supplierPaymentRepository.existsById(any())).thenReturn(false);
        when(documentNumberService.next(DocumentKind.PAYMENT_VOUCHER, LocalDate.of(2026, 9, 19)))
                .thenReturn("PAY/2026-27/0002");

        supplierPaymentService.pay(request(null, new BigDecimal("50000.00")));

        ArgumentCaptor<JournalPosting> captor = ArgumentCaptor.forClass(JournalPosting.class);
        verify(journalService).post(captor.capture());
        JournalPosting posting = captor.getValue();

        assertThat(posting.purpose()).isEqualTo(JournalPurpose.SUPPLIER_ADVANCE_PAID);
        assertThat(posting.lines()).hasSize(2);
        assertThat(posting.lines().get(0).accountCode()).isEqualTo("1300"); // SUPPLIER_ADVANCES
        assertThat(posting.lines().get(0).partyType()).isEqualTo("VENDOR");
        assertThat(posting.lines().get(0).partyId()).isEqualTo("V1");
        assertThat(posting.lines().get(0).debitAmount()).isEqualByComparingTo("50000.00");
        assertThat(posting.lines().get(1).accountCode()).isEqualTo("1110"); // BANK_ACCOUNTS
        assertThat(posting.lines().get(1).creditAmount()).isEqualByComparingTo("50000.00");
    }

    /** "Pay supplier" 5,000: one voucher and one ledger row; it clears the 3,500 bill, then 1,500 of the 2,000 one. */
    @Test
    void onePaymentClearsTheOpenBillsOldestFirst() {
        SupplierInvoice older = approvedBill(new BigDecimal("3500.00"));
        SupplierInvoice newer = approvedBill(new BigDecimal("2000.00"));
        newer.setId("SI2");
        when(vendorRepository.findById("V1")).thenReturn(Optional.of(vendor()));
        when(supplierPaymentRepository.existsById(any())).thenReturn(false);
        when(documentNumberService.next(DocumentKind.PAYMENT_VOUCHER, LocalDate.of(2026, 9, 19))).thenReturn("PV/2026-27/0009");
        java.util.List<SupplierPayment> saved = new java.util.ArrayList<>();
        when(supplierPaymentRepository.save(any())).thenAnswer(i -> { saved.add(i.getArgument(0)); return i.getArgument(0); });
        when(supplierPaymentRepository.findById(any())).thenAnswer(i -> saved.stream().filter(p -> p.getId().equals(i.getArgument(0))).findFirst());
        when(supplierPaymentRepository.findByVendorIdAndIsAdvanceTrueOrderByPaidOnAsc("V1")).thenAnswer(i -> saved.subList(0, 1));
        when(supplierInvoiceRepository.findByIdForUpdate("SI1")).thenReturn(Optional.of(older));
        when(supplierInvoiceRepository.findByIdForUpdate("SI2")).thenReturn(Optional.of(newer));
        when(supplierInvoiceRepository.findByVendorIdAndCurrencyCodeAndStatusInAndBalanceDueGreaterThanOrderByInvoiceDateAscCreatedDateAsc(
                org.mockito.ArgumentMatchers.eq("V1"), org.mockito.ArgumentMatchers.eq("INR"), any(), any())).thenReturn(List.of(older, newer));
        SupplierPaymentRequest r = request(null, new BigDecimal("5000.00"));
        r.setApplyToOpenBills(true);

        supplierPaymentService.pay(r);

        verify(supplierLedgerService, org.mockito.Mockito.times(1)).post(any());
        assertThat(older.getStatus()).isEqualTo(SupplierInvoiceStatus.PAID);
        assertThat(newer.getBalanceDue()).isEqualByComparingTo("500.00");
        assertThat(saved).hasSize(3);
    }

    @Test
    void applyingAnAdvancePostsTheAdvanceAppliedJournalWithNoSubsidiaryLedgerPost() {
        SupplierInvoice invoice = approvedBill(new BigDecimal("1000.00"));
        SupplierPayment advance = SupplierPayment.builder().id("D1").vendorId("V1").vendorName("Novotel Goa")
                .direction(SupplierPaymentDirection.PAYMENT).isAdvance(true)
                .amount(new BigDecimal("50000.00")).amountInr(new BigDecimal("50000.00")).build();
        when(supplierInvoiceRepository.findByIdForUpdate("SI1")).thenReturn(Optional.of(invoice));
        when(supplierPaymentRepository.findById("D1")).thenReturn(Optional.of(advance));
        when(supplierPaymentRepository.findByVendorIdAndIsAdvanceTrueOrderByPaidOnAsc("V1")).thenReturn(List.of(advance));
        when(supplierPaymentRepository.findByVendorIdAndAppliedFromAdvanceTrueOrderByPaidOnAsc("V1")).thenReturn(List.of());
        when(supplierPaymentRepository.existsById(any())).thenReturn(false);

        SupplierAdvanceApplyRequest request = new SupplierAdvanceApplyRequest();
        request.setAdvancePaymentId("D1");
        request.setAmount(new BigDecimal("400.00"));

        SupplierPaymentResponse response = supplierPaymentService.applyAdvance("SI1", request);

        assertThat(response.getAppliedFromAdvance()).isTrue();
        verify(supplierLedgerService, never()).post(any()); // AD-5 - no subsidiary-ledger row for an advance application

        ArgumentCaptor<JournalPosting> captor = ArgumentCaptor.forClass(JournalPosting.class);
        verify(journalService).post(captor.capture());
        JournalPosting posting = captor.getValue();

        assertThat(posting.purpose()).isEqualTo(JournalPurpose.SUPPLIER_ADVANCE_APPLIED);
        assertThat(posting.lines()).hasSize(2);
        assertThat(posting.lines().get(0).accountCode()).isEqualTo("2200"); // ACCOUNTS_PAYABLE
        assertThat(posting.lines().get(0).partyType()).isEqualTo("VENDOR");
        assertThat(posting.lines().get(0).partyId()).isEqualTo("V1");
        assertThat(posting.lines().get(0).debitAmount()).isEqualByComparingTo("400.00");
        assertThat(posting.lines().get(1).accountCode()).isEqualTo("1300"); // SUPPLIER_ADVANCES
        assertThat(posting.lines().get(1).partyType()).isEqualTo("VENDOR");
        assertThat(posting.lines().get(1).partyId()).isEqualTo("V1");
        assertThat(posting.lines().get(1).creditAmount()).isEqualByComparingTo("400.00");

        // Balanced, same invariant JournalService itself enforces - belt and braces at the call site too.
        BigDecimal debitTotal = posting.lines().stream().map(com.voyra.crm.models.JournalLinePosting::debitAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal creditTotal = posting.lines().stream().map(com.voyra.crm.models.JournalLinePosting::creditAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(debitTotal).isEqualByComparingTo(creditTotal);
    }

    @Test
    void reversingAPaymentAgainstABillReversesTheSettledJournalAndPostsALedgerRow() {
        SupplierPayment original = SupplierPayment.builder().id("P1").vendorId("V1").vendorName("Novotel Goa")
                .supplierInvoiceId("SI1").direction(SupplierPaymentDirection.PAYMENT)
                .currencyCode("INR").fxRateToInr(BigDecimal.ONE)
                .amount(new BigDecimal("400.00")).amountInr(new BigDecimal("400.00"))
                .paymentMode(PaymentMode.BANK_TRANSFER).isAdvance(false).appliedFromAdvance(false).build();
        SupplierInvoice invoice = approvedBill(new BigDecimal("1000.00"));
        invoice.setAmountPaid(new BigDecimal("400.00"));
        JournalEntry settledEntry = JournalEntry.builder().id("JE1").build();

        when(supplierPaymentRepository.findByIdForUpdate("P1")).thenReturn(Optional.of(original));
        when(supplierPaymentRepository.existsById(any())).thenReturn(false);
        when(documentNumberService.next(DocumentKind.PAYMENT_VOUCHER, LocalDate.now())).thenReturn("PAY/2026-27/0003");
        when(supplierInvoiceRepository.findByIdForUpdate("SI1")).thenReturn(Optional.of(invoice));
        when(journalEntryRepository.findBySourceTypeAndSourceIdAndPurpose(
                JournalSourceType.SUPPLIER_PAYMENT, "P1", JournalPurpose.SUPPLIER_PAYMENT_SETTLED))
                .thenReturn(Optional.of(settledEntry));
        when(journalEntryRepository.findBySourceTypeAndSourceIdAndPurpose(
                JournalSourceType.SUPPLIER_PAYMENT, "P1", JournalPurpose.SUPPLIER_ADVANCE_PAID))
                .thenReturn(Optional.empty());

        supplierPaymentService.reverse("P1", "wrong vendor");

        verify(journalService).reverse(org.mockito.ArgumentMatchers.eq("JE1"), any());
        verify(supplierLedgerService).post(any()); // the reversal row itself still posts a subsidiary-ledger entry
        assertThat(invoice.getAmountPaid()).isEqualByComparingTo("0.00");
    }

    /** Reversing a Pay supplier advance also undoes what it paid on bills, so those bills open again. */
    @Test
    void reversingAnAdvanceReopensTheBillsItWasAppliedTo() {
        SupplierPayment advance = SupplierPayment.builder().id("P9").vendorId("V1").vendorName("Novotel Goa")
                .direction(SupplierPaymentDirection.PAYMENT).currencyCode("INR").fxRateToInr(BigDecimal.ONE)
                .amount(new BigDecimal("400.00")).amountInr(new BigDecimal("400.00"))
                .paymentMode(PaymentMode.BANK_TRANSFER).isAdvance(true).appliedFromAdvance(false).build();
        SupplierPayment applied = SupplierPayment.builder().id("A1").vendorId("V1").vendorName("Novotel Goa")
                .supplierInvoiceId("SI1").direction(SupplierPaymentDirection.PAYMENT).currencyCode("INR").fxRateToInr(BigDecimal.ONE)
                .amount(new BigDecimal("400.00")).amountInr(new BigDecimal("400.00"))
                .paymentMode(PaymentMode.ADJUSTMENT).isAdvance(false).appliedFromAdvance(true)
                .notes("Applied from advance P9").build();
        SupplierInvoice invoice = approvedBill(new BigDecimal("1000.00"));
        invoice.setAmountPaid(new BigDecimal("400.00"));

        when(supplierPaymentRepository.findByIdForUpdate("P9")).thenReturn(Optional.of(advance));
        when(supplierPaymentRepository.findByIdForUpdate("A1")).thenReturn(Optional.of(applied));
        when(supplierPaymentRepository.findByVendorIdAndAppliedFromAdvanceTrueAndReversedAtIsNull("V1")).thenReturn(List.of(applied));
        when(supplierPaymentRepository.existsById(any())).thenReturn(false);
        when(documentNumberService.next(any(), any())).thenReturn("PAY/2026-27/0009");
        when(supplierInvoiceRepository.findByIdForUpdate("SI1")).thenReturn(Optional.of(invoice));
        when(journalEntryRepository.findBySourceTypeAndSourceIdAndPurpose(any(), any(), any())).thenReturn(Optional.empty());

        supplierPaymentService.reverse("P9", "paid the wrong supplier");

        assertThat(applied.getReversedAt()).isNotNull();
        assertThat(advance.getReversedAt()).isNotNull();
        assertThat(invoice.getAmountPaid()).isEqualByComparingTo("0.00");
    }

    /**
     * Decision 3.4 - the blocker's actual fix. A settlement rate HIGHER than the bill's own rate
     * costs more INR than AP cleared at - a realized LOSS, debited, and the three lines balance
     * exactly (apDebitInr + variance == amountInr).
     */
    @Test
    void settlingAForeignBillAtAHigherRateThanTheBillPostsARealizedForexLoss() {
        when(vendorRepository.findById("V1")).thenReturn(Optional.of(vendor()));
        SupplierInvoice invoice = foreignBill(new BigDecimal("1000.00"), new BigDecimal("83.000000"));
        when(supplierInvoiceRepository.findByIdForUpdate("SI1")).thenReturn(Optional.of(invoice));
        when(supplierPaymentRepository.existsById(any())).thenReturn(false);
        when(documentNumberService.next(DocumentKind.PAYMENT_VOUCHER, LocalDate.of(2026, 9, 19)))
                .thenReturn("PAY/2026-27/0004");

        SupplierPaymentRequest req = request("SI1", new BigDecimal("100.00"));
        req.setSettlementFxRate(new BigDecimal("84.000000"));
        SupplierPaymentResponse response = supplierPaymentService.pay(req);

        assertThat(response.getFxVarianceInr()).isEqualByComparingTo("100.00");

        ArgumentCaptor<JournalPosting> captor = ArgumentCaptor.forClass(JournalPosting.class);
        verify(journalService).post(captor.capture());
        JournalPosting posting = captor.getValue();

        assertThat(posting.lines()).hasSize(3);
        assertThat(posting.lines().get(0).accountCode()).isEqualTo("2200");
        assertThat(posting.lines().get(0).debitAmount()).isEqualByComparingTo("8300.00"); // bill rate
        assertThat(posting.lines().get(1).accountCode()).isEqualTo("5700"); // REALIZED_FOREX_LOSS
        assertThat(posting.lines().get(1).debitAmount()).isEqualByComparingTo("100.00");
        assertThat(posting.lines().get(2).accountCode()).isEqualTo("1110");
        assertThat(posting.lines().get(2).creditAmount()).isEqualByComparingTo("8400.00"); // settlement rate

        BigDecimal debitTotal = posting.lines().stream().map(com.voyra.crm.models.JournalLinePosting::debitAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal creditTotal = posting.lines().stream().map(com.voyra.crm.models.JournalLinePosting::creditAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(debitTotal).isEqualByComparingTo(creditTotal);
    }

    @Test
    void settlingAForeignBillAtALowerRateThanTheBillPostsARealizedForexGain() {
        when(vendorRepository.findById("V1")).thenReturn(Optional.of(vendor()));
        SupplierInvoice invoice = foreignBill(new BigDecimal("1000.00"), new BigDecimal("83.000000"));
        when(supplierInvoiceRepository.findByIdForUpdate("SI1")).thenReturn(Optional.of(invoice));
        when(supplierPaymentRepository.existsById(any())).thenReturn(false);
        when(documentNumberService.next(DocumentKind.PAYMENT_VOUCHER, LocalDate.of(2026, 9, 19)))
                .thenReturn("PAY/2026-27/0005");

        SupplierPaymentRequest req = request("SI1", new BigDecimal("100.00"));
        req.setSettlementFxRate(new BigDecimal("82.000000"));
        SupplierPaymentResponse response = supplierPaymentService.pay(req);

        assertThat(response.getFxVarianceInr()).isEqualByComparingTo("-100.00");

        ArgumentCaptor<JournalPosting> captor = ArgumentCaptor.forClass(JournalPosting.class);
        verify(journalService).post(captor.capture());
        JournalPosting posting = captor.getValue();

        assertThat(posting.lines()).hasSize(3);
        assertThat(posting.lines().get(0).accountCode()).isEqualTo("2200");
        assertThat(posting.lines().get(0).debitAmount()).isEqualByComparingTo("8300.00");
        assertThat(posting.lines().get(1).accountCode()).isEqualTo("4700"); // REALIZED_FOREX_GAIN
        assertThat(posting.lines().get(1).creditAmount()).isEqualByComparingTo("100.00");
        assertThat(posting.lines().get(2).accountCode()).isEqualTo("1110");
        assertThat(posting.lines().get(2).creditAmount()).isEqualByComparingTo("8200.00");

        BigDecimal debitTotal = posting.lines().stream().map(com.voyra.crm.models.JournalLinePosting::debitAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal creditTotal = posting.lines().stream().map(com.voyra.crm.models.JournalLinePosting::creditAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(debitTotal).isEqualByComparingTo(creditTotal);
    }

    @Test
    void anInrBillsVarianceIsAlwaysExactlyZeroRegardlessOfAnySettlementRateField() {
        when(vendorRepository.findById("V1")).thenReturn(Optional.of(vendor()));
        SupplierInvoice invoice = approvedBill(new BigDecimal("1000.00"));
        when(supplierInvoiceRepository.findByIdForUpdate("SI1")).thenReturn(Optional.of(invoice));
        when(supplierPaymentRepository.existsById(any())).thenReturn(false);
        when(documentNumberService.next(DocumentKind.PAYMENT_VOUCHER, LocalDate.of(2026, 9, 19)))
                .thenReturn("PAY/2026-27/0006");

        SupplierPaymentResponse response = supplierPaymentService.pay(request("SI1", new BigDecimal("400.00")));

        assertThat(response.getFxVarianceInr()).isNull(); // INR bill - nothing to vary, never even looked at settlementFxRate
        verify(exchangeRateProvider, never()).resolve(any(), any(), any());
    }

    @Test
    void anExplicitSettlementRateOnTheRequestIsUsedWithoutConsultingTheExchangeRateTable() {
        when(vendorRepository.findById("V1")).thenReturn(Optional.of(vendor()));
        SupplierInvoice invoice = foreignBill(new BigDecimal("1000.00"), new BigDecimal("83.000000"));
        when(supplierInvoiceRepository.findByIdForUpdate("SI1")).thenReturn(Optional.of(invoice));
        when(supplierPaymentRepository.existsById(any())).thenReturn(false);
        when(documentNumberService.next(DocumentKind.PAYMENT_VOUCHER, LocalDate.of(2026, 9, 19)))
                .thenReturn("PAY/2026-27/0007");

        SupplierPaymentRequest req = request("SI1", new BigDecimal("100.00"));
        req.setSettlementFxRate(new BigDecimal("85.000000"));
        supplierPaymentService.pay(req);

        verify(exchangeRateProvider, never()).resolve(any(), any(), any());
    }

    @Test
    void withNoExplicitRateTheDailyExchangeRateTableIsConsultedForThePaymentDate() {
        when(vendorRepository.findById("V1")).thenReturn(Optional.of(vendor()));
        SupplierInvoice invoice = foreignBill(new BigDecimal("1000.00"), new BigDecimal("83.000000"));
        when(supplierInvoiceRepository.findByIdForUpdate("SI1")).thenReturn(Optional.of(invoice));
        when(supplierPaymentRepository.existsById(any())).thenReturn(false);
        when(documentNumberService.next(DocumentKind.PAYMENT_VOUCHER, LocalDate.of(2026, 9, 19)))
                .thenReturn("PAY/2026-27/0009");
        when(exchangeRateProvider.resolve("USD", "INR", LocalDate.of(2026, 9, 19)))
                .thenReturn(new ResolvedExchangeRate(new BigDecimal("84.000000"), FxRateSource.DAILY_TABLE, LocalDate.of(2026, 9, 19)));

        SupplierPaymentResponse response = supplierPaymentService.pay(request("SI1", new BigDecimal("100.00")));

        assertThat(response.getSettlementFxRate()).isEqualByComparingTo("84.000000");
        assertThat(response.getFxVarianceInr()).isEqualByComparingTo("100.00"); // 84 > 83 - a loss
    }

    @Test
    void aMissingExchangeRateFallsBackToTheBillsOwnRateRatherThanBlockingThePayment() {
        when(vendorRepository.findById("V1")).thenReturn(Optional.of(vendor()));
        SupplierInvoice invoice = foreignBill(new BigDecimal("1000.00"), new BigDecimal("83.000000"));
        when(supplierInvoiceRepository.findByIdForUpdate("SI1")).thenReturn(Optional.of(invoice));
        when(supplierPaymentRepository.existsById(any())).thenReturn(false);
        when(documentNumberService.next(DocumentKind.PAYMENT_VOUCHER, LocalDate.of(2026, 9, 19)))
                .thenReturn("PAY/2026-27/0008");
        when(exchangeRateProvider.resolve("USD", "INR", LocalDate.of(2026, 9, 19)))
                .thenThrow(new IllegalStateException("No exchange rate on or before 2026-09-19 for USD/INR"));

        SupplierPaymentResponse response = supplierPaymentService.pay(request("SI1", new BigDecimal("100.00")));

        assertThat(response.getFxVarianceInr()).isEqualByComparingTo("0.00"); // fell back to the bill's own rate - no variance
    }

    /** The mirror's bill is paid from the Voyra wallet: Dr payable, Cr 1310 "Voyra Wallet (advance to supplier)" - never Bank 1110. */
    @Test
    void mirrorSupplierBillPostsToVoyraWalletNot1110() {
        when(vendorRepository.findById("V1")).thenReturn(Optional.of(vendor()));
        SupplierInvoice invoice = approvedBill(new BigDecimal("5400.00"));
        when(supplierInvoiceRepository.findByIdForUpdate("SI1")).thenReturn(Optional.of(invoice));
        when(ledgerAccountResolver.voyraWalletCode()).thenReturn("1310");
        SupplierPaymentRequest request = request("SI1", new BigDecimal("5400.00"));
        request.setPaymentMode(PaymentMode.VOYRA_WALLET);

        org.mockito.Mockito.doReturn("INR").when(baseCurrency).code();
        com.voyra.crm.security.SystemActor.call("T1", () -> supplierPaymentService.pay(request));

        ArgumentCaptor<JournalPosting> captor = ArgumentCaptor.forClass(JournalPosting.class);
        verify(journalService).post(captor.capture());
        assertThat(captor.getValue().lines()).extracting(l -> l.accountCode()).containsExactly("2200", "1310");
        assertThat(captor.getValue().lines().get(1).creditAmount()).isEqualByComparingTo("5400.00");
    }

    @Test
    void crmEditOfAMirrorIs409() {
        when(vendorRepository.findById("V1")).thenReturn(Optional.of(vendor()));
        SupplierInvoice invoice = approvedBill(new BigDecimal("5400.00"));
        invoice.setBookingId("B1");
        when(supplierInvoiceRepository.findByIdForUpdate("SI1")).thenReturn(Optional.of(invoice));
        when(bookingRepository.existsByIdAndPlatformBookingIdIsNotNull("B1")).thenReturn(true);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> supplierPaymentService.pay(request("SI1", new BigDecimal("100.00"))))
                .isInstanceOf(com.voyra.crm.exception.BookingConflictException.class)
                .hasMessage("Managed from Platform Bookings");
        verify(supplierPaymentRepository, org.mockito.Mockito.never()).save(any());
    }

    /** Only Voyra (the system actor writing a mirror) pays from the Voyra wallet - no user picks it on a bill or an advance. */
    @Test
    void voyraWalletPaymentsAreRecordedByVoyraOnly() {
        SupplierPaymentRequest bill = request("SI1", new BigDecimal("100.00"));
        bill.setPaymentMode(PaymentMode.VOYRA_WALLET);
        SupplierPaymentRequest advance = request(null, new BigDecimal("100.00"));
        advance.setPaymentMode(PaymentMode.VOYRA_WALLET);

        for (SupplierPaymentRequest r : List.of(bill, advance)) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> supplierPaymentService.pay(r))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("Voyra Wallet payments are recorded by Voyra only");
        }

        SupplierPayment voyraAdvance = SupplierPayment.builder().id("D1").vendorId("V1").isAdvance(true)
                .paymentMode(PaymentMode.VOYRA_WALLET).amount(new BigDecimal("500.00")).build();
        when(supplierInvoiceRepository.findByIdForUpdate("SI1")).thenReturn(Optional.of(approvedBill(new BigDecimal("1000.00"))));
        when(supplierPaymentRepository.findById("D1")).thenReturn(Optional.of(voyraAdvance));
        SupplierAdvanceApplyRequest apply = new SupplierAdvanceApplyRequest();
        apply.setAdvancePaymentId("D1");
        apply.setAmount(new BigDecimal("100.00"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> supplierPaymentService.applyAdvance("SI1", apply))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Voyra Wallet payments are recorded by Voyra only");
        verify(supplierPaymentRepository, never()).save(any());
        verify(journalService, never()).post(any());
    }
}
