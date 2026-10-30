package com.voyra.crm.service;

import com.voyra.crm.dto.SupplierAdvanceApplyRequest;
import com.voyra.crm.dto.SupplierPaymentRequest;
import com.voyra.crm.dto.SupplierPaymentResponse;
import com.voyra.crm.dto.SupplierUnallocatedAdvancesResponse;
import com.voyra.crm.entity.JournalEntry;
import com.voyra.crm.entity.SupplierInvoice;
import com.voyra.crm.entity.SupplierPayment;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.entity.Vendor;
import com.voyra.crm.enums.DocumentKind;
import com.voyra.crm.enums.JournalPurpose;
import com.voyra.crm.enums.JournalSourceType;
import com.voyra.crm.enums.PaymentMode;
import com.voyra.crm.enums.SupplierInvoiceStatus;
import com.voyra.crm.enums.SupplierLedgerEntryType;
import com.voyra.crm.enums.SupplierLedgerSourceType;
import com.voyra.crm.enums.SupplierPaymentDirection;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.models.JournalLinePosting;
import com.voyra.crm.models.JournalPosting;
import com.voyra.crm.models.SupplierLedgerPosting;
import com.voyra.crm.repository.BookingRepository;
import com.voyra.crm.repository.JournalEntryRepository;
import com.voyra.crm.repository.SupplierInvoiceRepository;
import com.voyra.crm.repository.SupplierPaymentRepository;
import com.voyra.crm.repository.TenantRepository;
import com.voyra.crm.repository.VendorRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Branches of {@link SupplierPaymentService} the journal-content tests leave open. */
@ExtendWith(MockitoExtension.class)
class SupplierPaymentServiceCoverageTest {

    @Mock private SupplierPaymentRepository supplierPaymentRepository;
    @Mock private SupplierInvoiceRepository supplierInvoiceRepository;
    @Mock private VendorRepository vendorRepository;
    @Mock private TenantRepository tenantRepository;
    @Mock private DocumentNumberService documentNumberService;
    @Mock private SupplierLedgerService supplierLedgerService;
    @Mock private AuditService auditService;
    @Mock private JournalService journalService;
    @Mock private JournalEntryRepository journalEntryRepository;
    @Mock private ExchangeRateProvider exchangeRateProvider;
    @Mock private BookingRepository bookingRepository;
    @Mock private LedgerAccountResolver ledgerAccountResolver;
    @Spy private BaseCurrency baseCurrency = new BaseCurrency(null);
    @InjectMocks private SupplierPaymentService service;

    private static final LocalDate PAID_ON = LocalDate.of(2026, 9, 19);

    @BeforeEach
    void auth() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal("AC1", "acct", UserType.ACCOUNTANT, "T1"), null, List.of()));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static Vendor vendor() {
        return Vendor.builder().id("V1").name("Novotel Goa").build();
    }

    private static SupplierInvoice bill(String id, String total) {
        BigDecimal t = new BigDecimal(total);
        return SupplierInvoice.builder().id(id).vendorId("V1").vendorName("Novotel Goa").invoiceDate(LocalDate.of(2026, 9, 1))
                .status(SupplierInvoiceStatus.APPROVED).currencyCode("INR").fxRateToInr(BigDecimal.ONE)
                .grandTotal(t).grandTotalInr(t).amountPaid(BigDecimal.ZERO).creditNoteTotal(BigDecimal.ZERO)
                .balanceDue(t).balanceDueInr(t).build();
    }

    private static SupplierPaymentRequest req(String invoiceId, String amount) {
        SupplierPaymentRequest r = new SupplierPaymentRequest();
        r.setVendorId("V1");
        r.setSupplierInvoiceId(invoiceId);
        r.setAmount(new BigDecimal(amount));
        r.setPaymentMode(PaymentMode.BANK_TRANSFER);
        r.setPaidOn(PAID_ON);
        return r;
    }

    private static SupplierPayment advance(String id, String amount) {
        return SupplierPayment.builder().id(id).vendorId("V1").vendorName("Novotel Goa").voucherNumber("PV-" + id)
                .direction(SupplierPaymentDirection.PAYMENT).currencyCode("INR").fxRateToInr(BigDecimal.ONE)
                .amount(new BigDecimal(amount)).amountInr(new BigDecimal(amount)).tdsWithheld(BigDecimal.ZERO)
                .paymentMode(PaymentMode.BANK_TRANSFER).isAdvance(true).appliedFromAdvance(false)
                .paidOn(PAID_ON).build();
    }

    // ---------------------------------------------------------------- pay

    @Test
    void refusesAFutureDateAnUnknownVendorAnUnknownBillAndAnotherVendorsBill() {
        SupplierPaymentRequest future = req(null, "10");
        future.setPaidOn(LocalDate.now().plusDays(3));
        assertThatThrownBy(() -> service.pay(future))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("The payment date can't be in the future");

        SupplierPaymentRequest unknownVendor = req(null, "10");
        unknownVendor.setVendorId("V9");
        assertThatThrownBy(() -> service.pay(unknownVendor))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Vendor not found: V9");

        when(vendorRepository.findById("V1")).thenReturn(Optional.of(vendor()));
        when(supplierInvoiceRepository.findByIdForUpdate("SI9")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.pay(req("SI9", "10")))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Supplier bill not found: SI9");

        SupplierInvoice foreign = bill("SI2", "100");
        foreign.setVendorId("V2");
        when(supplierInvoiceRepository.findByIdForUpdate("SI2")).thenReturn(Optional.of(foreign));
        assertThatThrownBy(() -> service.pay(req("SI2", "10")))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("This bill does not belong to the selected vendor");
        verify(supplierPaymentRepository, never()).save(any());
    }

    @Test
    void aBlankBillIdIsACashAdvanceWithTdsAndNoBillsAreCleared() {
        when(vendorRepository.findById("V1")).thenReturn(Optional.of(vendor()));
        when(documentNumberService.next(DocumentKind.PAYMENT_VOUCHER, PAID_ON)).thenReturn("PV-1");
        SupplierPaymentRequest r = req("  ", "2500.00");
        r.setPaymentMode(PaymentMode.CASH);
        r.setTdsWithheld(new BigDecimal("25.00"));
        r.setApplyToOpenBills(false);
        r.setInstrumentRef("CHQ-1");
        r.setBankAccountLabel("HDFC");
        r.setNotes("deposit");

        SupplierPaymentResponse out = service.pay(r);

        assertThat(out.getIsAdvance()).isTrue();
        assertThat(out.getSupplierInvoiceId()).isNull();
        assertThat(out.getTdsWithheld()).isEqualByComparingTo("25.00");
        assertThat(out.getAmountInr()).isEqualByComparingTo("2500.00");
        assertThat(out.getFinancialYear()).isEqualTo("2026-27");
        assertThat(out.getInstrumentRef()).isEqualTo("CHQ-1");
        ArgumentCaptor<SupplierLedgerPosting> ledger = ArgumentCaptor.forClass(SupplierLedgerPosting.class);
        verify(supplierLedgerService).post(ledger.capture());
        assertThat(ledger.getValue().entryType()).isEqualTo(SupplierLedgerEntryType.ADVANCE_PAID);
        assertThat(ledger.getValue().narration()).isEqualTo("Payment PV-1 recorded");
        assertThat(ledger.getValue().debitAmount()).isEqualByComparingTo("2500.00");
        ArgumentCaptor<JournalPosting> journal = ArgumentCaptor.forClass(JournalPosting.class);
        verify(journalService).post(journal.capture());
        assertThat(journal.getValue().purpose()).isEqualTo(JournalPurpose.SUPPLIER_ADVANCE_PAID);
        assertThat(journal.getValue().lines()).extracting(JournalLinePosting::accountCode).containsExactly("1300", "1100");
        assertThat(journal.getValue().lines().get(1).creditAmount()).isEqualByComparingTo("2500.00");
        verify(supplierInvoiceRepository, never())
                .findByVendorIdAndCurrencyCodeAndStatusInAndBalanceDueGreaterThanOrderByInvoiceDateAscCreatedDateAsc(any(), any(), any(), any());
    }

    @Test
    void aZeroPaymentAgainstABillWithNoBalanceFigurePostsNoJournal() {
        when(vendorRepository.findById("V1")).thenReturn(Optional.of(vendor()));
        SupplierInvoice b = bill("SI1", "100.00");
        b.setBalanceDue(null);
        b.setBookingId("B1");
        when(supplierInvoiceRepository.findByIdForUpdate("SI1")).thenReturn(Optional.of(b));
        when(bookingRepository.existsByIdAndPlatformBookingIdIsNotNull("B1")).thenReturn(false);

        SupplierPaymentResponse out = service.pay(req("SI1", "0.00"));

        assertThat(out.getAmountInr()).isEqualByComparingTo("0.00");
        assertThat(out.getBookingId()).isEqualTo("B1");
        verify(journalService, never()).post(any());
        verify(supplierLedgerService).post(any());
        assertThat(b.getBalanceDue()).isEqualByComparingTo("100.00");
    }

    @Test
    void clearingOpenBillsStopsOnceTheMoneyRunsOut() {
        SupplierInvoice first = bill("SI1", "1000.00");
        SupplierInvoice second = bill("SI2", "700.00");
        when(vendorRepository.findById("V1")).thenReturn(Optional.of(vendor()));
        when(documentNumberService.next(DocumentKind.PAYMENT_VOUCHER, PAID_ON)).thenReturn("PV-7");
        List<SupplierPayment> saved = new ArrayList<>();
        when(supplierPaymentRepository.save(any())).thenAnswer(i -> { saved.add(i.getArgument(0)); return i.getArgument(0); });
        when(supplierPaymentRepository.findById(any())).thenAnswer(i -> saved.stream().filter(p -> p.getId().equals(i.getArgument(0))).findFirst());
        when(supplierPaymentRepository.findByVendorIdAndIsAdvanceTrueOrderByPaidOnAsc("V1")).thenAnswer(i -> saved.subList(0, 1));
        when(supplierInvoiceRepository.findByIdForUpdate("SI1")).thenReturn(Optional.of(first));
        when(supplierInvoiceRepository.findByVendorIdAndCurrencyCodeAndStatusInAndBalanceDueGreaterThanOrderByInvoiceDateAscCreatedDateAsc(
                eq("V1"), eq("INR"), any(), any())).thenReturn(List.of(first, second));
        SupplierPaymentRequest r = req(null, "1000.00");
        r.setApplyToOpenBills(true);

        service.pay(r);

        assertThat(first.getStatus()).isEqualTo(SupplierInvoiceStatus.PAID);
        assertThat(second.getBalanceDue()).isEqualByComparingTo("700.00");
        assertThat(saved).hasSize(2);
        ArgumentCaptor<SupplierLedgerPosting> ledger = ArgumentCaptor.forClass(SupplierLedgerPosting.class);
        verify(supplierLedgerService).post(ledger.capture());
        assertThat(ledger.getValue().narration()).isEqualTo("Payment PV-7 - clears open bills oldest first");
    }

    // ---------------------------------------------------------------- wallet credit

    @Test
    void aSupplierRefundKeptInTheWalletIsAnAdvanceWithOneLedgerRowAndNoJournal() {
        when(vendorRepository.findById("V1")).thenReturn(Optional.of(vendor()));
        when(documentNumberService.next(DocumentKind.PAYMENT_VOUCHER, PAID_ON)).thenReturn("PV-W");

        SupplierPaymentResponse out = service.creditWallet("V1", new BigDecimal("1800.00"), PAID_ON, "Refund on cancel");

        assertThat(out.getIsAdvance()).isTrue();
        assertThat(out.getAppliedFromAdvance()).isFalse();
        assertThat(out.getPaymentMode()).isEqualTo(PaymentMode.ADJUSTMENT);
        assertThat(out.getAmount()).isEqualByComparingTo("1800.00");
        assertThat(out.getAmountInr()).isEqualByComparingTo("1800.00");
        assertThat(out.getBookingId()).isNull();
        assertThat(out.getNotes()).isEqualTo("Refund on cancel");
        ArgumentCaptor<SupplierLedgerPosting> ledger = ArgumentCaptor.forClass(SupplierLedgerPosting.class);
        verify(supplierLedgerService).post(ledger.capture());
        SupplierLedgerPosting p = ledger.getValue();
        assertThat(p.entryType()).isEqualTo(SupplierLedgerEntryType.ADVANCE_PAID);
        assertThat(p.sourceType()).isEqualTo(SupplierLedgerSourceType.SUPPLIER_PAYMENT);
        assertThat(p.documentNumber()).isEqualTo("PV-W");
        assertThat(p.debitAmount()).isEqualByComparingTo("1800.00");
        assertThat(p.creditAmount()).isEqualByComparingTo("0");
        assertThat(p.debitAmountInr()).isEqualByComparingTo("1800.00");
        verify(journalService, never()).post(any());
        verify(auditService).recordCreate(com.voyra.crm.enums.AuditEntityType.SUPPLIER_PAYMENT, out.getId(), "PV-W");
    }

    // ---------------------------------------------------------------- apply advance

    private SupplierAdvanceApplyRequest apply(String advanceId, String amount) {
        SupplierAdvanceApplyRequest a = new SupplierAdvanceApplyRequest();
        a.setAdvancePaymentId(advanceId);
        a.setAmount(new BigDecimal(amount));
        return a;
    }

    @Test
    void applyRefusesAMissingInactiveOrForeignAdvance() {
        when(supplierInvoiceRepository.findByIdForUpdate("SI1")).thenReturn(Optional.of(bill("SI1", "1000.00")));
        when(supplierPaymentRepository.findById("D0")).thenReturn(Optional.empty());
        SupplierPayment notAdvance = advance("D1", "100");
        notAdvance.setIsAdvance(false);
        when(supplierPaymentRepository.findById("D1")).thenReturn(Optional.of(notAdvance));
        SupplierPayment reversed = advance("D2", "100");
        reversed.setReversedAt(LocalDateTime.now());
        when(supplierPaymentRepository.findById("D2")).thenReturn(Optional.of(reversed));
        SupplierPayment otherVendor = advance("D3", "100");
        otherVendor.setVendorId("V2");
        when(supplierPaymentRepository.findById("D3")).thenReturn(Optional.of(otherVendor));

        assertThatThrownBy(() -> service.applyAdvance("SI1", apply("D0", "10")))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Advance payment not found: D0");
        assertThatThrownBy(() -> service.applyAdvance("SI1", apply("D1", "10")))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("This payment is not an active advance");
        assertThatThrownBy(() -> service.applyAdvance("SI1", apply("D2", "10")))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("This payment is not an active advance");
        assertThatThrownBy(() -> service.applyAdvance("SI1", apply("D3", "10")))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("This advance belongs to a different vendor");
        verify(supplierPaymentRepository, never()).save(any());
    }

    @Test
    void applyRefusesMoreThanTheActiveAdvancePoolLeft() {
        when(supplierInvoiceRepository.findByIdForUpdate("SI1")).thenReturn(Optional.of(bill("SI1", "1000.00")));
        SupplierPayment d1 = advance("D1", "500.00");
        when(supplierPaymentRepository.findById("D1")).thenReturn(Optional.of(d1));
        SupplierPayment reversedAdvance = advance("D2", "900.00");
        reversedAdvance.setReversedAt(LocalDateTime.now());
        SupplierPayment advanceReversal = advance("D3", "-300.00");
        advanceReversal.setReversesPaymentId("D9");
        when(supplierPaymentRepository.findByVendorIdAndIsAdvanceTrueOrderByPaidOnAsc("V1"))
                .thenReturn(List.of(d1, reversedAdvance, advanceReversal));
        SupplierPayment used = advance("A1", "350.00");
        SupplierPayment usedButReversed = advance("A2", "200.00");
        usedButReversed.setReversedAt(LocalDateTime.now());
        SupplierPayment usedReversal = advance("A3", "-200.00");
        usedReversal.setReversesPaymentId("A2");
        when(supplierPaymentRepository.findByVendorIdAndAppliedFromAdvanceTrueOrderByPaidOnAsc("V1"))
                .thenReturn(List.of(used, usedButReversed, usedReversal));

        assertThatThrownBy(() -> service.applyAdvance("SI1", apply("D1", "150.01")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Only 150.00 of advance remains unapplied for this vendor");
        verify(supplierPaymentRepository, never()).save(any());
    }

    @Test
    void applyingNothingFromAnAdvancePostsNoJournalAndNamesTheBillByVendorAndDateOtherwise() {
        SupplierInvoice b = bill("SI1", "1000.00");
        when(supplierInvoiceRepository.findByIdForUpdate("SI1")).thenReturn(Optional.of(b));
        SupplierPayment d1 = advance("D1", "500.00");
        when(supplierPaymentRepository.findById("D1")).thenReturn(Optional.of(d1));
        when(supplierPaymentRepository.findByVendorIdAndIsAdvanceTrueOrderByPaidOnAsc("V1")).thenReturn(List.of(d1));

        SupplierPaymentResponse zero = service.applyAdvance("SI1", apply("D1", "0.00"));
        assertThat(zero.getAppliedFromAdvance()).isTrue();
        verify(journalService, never()).post(any());

        service.applyAdvance("SI1", apply("D1", "200.00"));
        b.setSupplierInvoiceNumber("NG-77");
        service.applyAdvance("SI1", apply("D1", "100.00"));
        ArgumentCaptor<JournalPosting> journal = ArgumentCaptor.forClass(JournalPosting.class);
        verify(journalService, org.mockito.Mockito.times(2)).post(journal.capture());
        JournalPosting first = journal.getAllValues().get(0);
        assertThat(first.narration()).isEqualTo("Advance applied to bill Novotel Goa of 2026-09-01");
        assertThat(first.purpose()).isEqualTo(JournalPurpose.SUPPLIER_ADVANCE_APPLIED);
        assertThat(first.lines()).extracting(JournalLinePosting::accountCode).containsExactly("2200", "1300");
        assertThat(first.lines().get(0).debitAmount()).isEqualByComparingTo("200.00");
        assertThat(first.lines().get(1).creditAmount()).isEqualByComparingTo("200.00");
        assertThat(journal.getAllValues().get(1).narration()).isEqualTo("Advance applied to bill NG-77");
        assertThat(b.getAmountPaid()).isEqualByComparingTo("300.00");
        assertThat(b.getStatus()).isEqualTo(SupplierInvoiceStatus.PARTIALLY_PAID);
        verifyNoInteractions(supplierLedgerService);
    }

    // ---------------------------------------------------------------- reverse

    @Test
    void reverseRefusesAMissingPaymentAReversalAndAnAlreadyReversedOne() {
        when(supplierPaymentRepository.findByIdForUpdate("P0")).thenReturn(Optional.empty());
        SupplierPayment reversal = advance("P1", "-10");
        reversal.setReversesPaymentId("P9");
        when(supplierPaymentRepository.findByIdForUpdate("P1")).thenReturn(Optional.of(reversal));
        SupplierPayment done = advance("P2", "10");
        done.setReversedAt(LocalDateTime.now());
        when(supplierPaymentRepository.findByIdForUpdate("P2")).thenReturn(Optional.of(done));

        assertThatThrownBy(() -> service.reverse("P0", "x"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Payment not found: P0");
        assertThatThrownBy(() -> service.reverse("P1", "x"))
                .isInstanceOf(IllegalStateException.class).hasMessage("Cannot reverse a reversing entry");
        assertThatThrownBy(() -> service.reverse("P2", "x"))
                .isInstanceOf(IllegalStateException.class).hasMessage("This payment has already been reversed");
        verify(supplierPaymentRepository, never()).save(any());
    }

    @Test
    void reversingAForeignSettlementNegatesTheVarianceAndNamesTheOriginalVoucher() {
        SupplierPayment original = SupplierPayment.builder().id("P1").voucherNumber("PV-1").vendorId("V1").vendorName("Novotel Goa")
                .supplierInvoiceId("SI1").direction(SupplierPaymentDirection.PAYMENT).currencyCode("USD")
                .fxRateToInr(new BigDecimal("84")).settlementFxRate(new BigDecimal("84")).fxVarianceInr(new BigDecimal("100.00"))
                .amount(new BigDecimal("100.00")).amountInr(new BigDecimal("8400.00")).tdsWithheld(BigDecimal.ZERO)
                .paymentMode(PaymentMode.BANK_TRANSFER).isAdvance(false).appliedFromAdvance(false).build();
        SupplierInvoice usd = bill("SI1", "100.00");
        usd.setCurrencyCode("USD");
        usd.setFxRateToInr(new BigDecimal("83"));
        usd.setAmountPaid(new BigDecimal("100.00"));
        when(supplierPaymentRepository.findByIdForUpdate("P1")).thenReturn(Optional.of(original));
        when(supplierPaymentRepository.findById("P1")).thenReturn(Optional.of(original));
        when(documentNumberService.next(DocumentKind.PAYMENT_VOUCHER, LocalDate.now())).thenReturn("PV-R");
        when(supplierInvoiceRepository.findByIdForUpdate("SI1")).thenReturn(Optional.of(usd));
        JournalEntry settled = JournalEntry.builder().id("JE1").build();
        when(journalEntryRepository.findBySourceTypeAndSourceIdAndPurpose(JournalSourceType.SUPPLIER_PAYMENT, "P1",
                JournalPurpose.SUPPLIER_PAYMENT_SETTLED)).thenReturn(Optional.of(settled));
        when(journalEntryRepository.findBySourceTypeAndSourceIdAndPurpose(JournalSourceType.SUPPLIER_PAYMENT, "P1",
                JournalPurpose.SUPPLIER_ADVANCE_PAID)).thenReturn(Optional.empty());

        SupplierPaymentResponse out = service.reverse("P1", "bank bounced");

        assertThat(out.getFxVarianceInr()).isEqualByComparingTo("-100.00");
        assertThat(out.getAmount()).isEqualByComparingTo("-100.00");
        assertThat(out.getAmountInr()).isEqualByComparingTo("-8400.00");
        assertThat(out.getReversesPaymentId()).isEqualTo("P1");
        ArgumentCaptor<SupplierLedgerPosting> ledger = ArgumentCaptor.forClass(SupplierLedgerPosting.class);
        verify(supplierLedgerService).post(ledger.capture());
        SupplierLedgerPosting p = ledger.getValue();
        assertThat(p.narration()).isEqualTo("Payment PV-R reverses PV-1");
        assertThat(p.entryType()).isEqualTo(SupplierLedgerEntryType.PAYMENT_MADE);
        // credit at the bill's rate: 8400 less the 100 variance
        assertThat(p.creditAmount()).isEqualByComparingTo("100.00");
        assertThat(p.creditAmountInr()).isEqualByComparingTo("8300.00");
        assertThat(p.debitAmount()).isEqualByComparingTo("0");
        verify(journalService).reverse("JE1", "Payment PV-1 reversed");
        assertThat(original.getReversalReason()).isEqualTo("bank bounced");
        assertThat(usd.getAmountPaid()).isEqualByComparingTo("0.00");
        assertThat(usd.getBalanceDueInr()).isEqualByComparingTo("8300.00");
    }

    @Test
    void reversingAnAppliedAdvanceGetsNoVoucherNoLedgerRowAndReversesTheAppliedJournal() {
        SupplierPayment applied = SupplierPayment.builder().id("A1").vendorId("V1").vendorName("Novotel Goa")
                .supplierInvoiceId("SI1").direction(SupplierPaymentDirection.PAYMENT).currencyCode("INR").fxRateToInr(BigDecimal.ONE)
                .amount(new BigDecimal("300.00")).amountInr(new BigDecimal("300.00")).tdsWithheld(BigDecimal.ZERO)
                .paymentMode(PaymentMode.ADJUSTMENT).isAdvance(false).appliedFromAdvance(true).build();
        SupplierInvoice b = bill("SI1", "1000.00");
        b.setAmountPaid(new BigDecimal("300.00"));
        when(supplierPaymentRepository.findByIdForUpdate("A1")).thenReturn(Optional.of(applied));
        when(supplierInvoiceRepository.findByIdForUpdate("SI1")).thenReturn(Optional.of(b));
        when(journalEntryRepository.findBySourceTypeAndSourceIdAndPurpose(JournalSourceType.SUPPLIER_PAYMENT, "A1",
                JournalPurpose.SUPPLIER_ADVANCE_APPLIED)).thenReturn(Optional.of(JournalEntry.builder().id("JE9").build()));

        SupplierPaymentResponse out = service.reverse("A1", "wrong bill");

        assertThat(out.getVoucherNumber()).isNull();
        assertThat(out.getFinancialYear()).isNull();
        assertThat(out.getFxVarianceInr()).isNull();
        verifyNoInteractions(documentNumberService, supplierLedgerService);
        verify(journalService).reverse("JE9", "Payment A1 reversed");
        assertThat(b.getAmountPaid()).isEqualByComparingTo("0.00");
        assertThat(b.getStatus()).isEqualTo(SupplierInvoiceStatus.APPROVED);
    }

    @Test
    void reversingAnAdvanceLeavesAppliedRowsOfOtherAdvancesAndReversalRowsAlone() {
        SupplierPayment adv = advance("P9", "400.00");
        SupplierPayment otherAdvancesRow = advance("A1", "100.00");
        otherAdvancesRow.setNotes("Applied from advance P8");
        SupplierPayment reversalRow = advance("A2", "-100.00");
        reversalRow.setNotes("Applied from advance P9");
        reversalRow.setReversesPaymentId("A0");
        when(supplierPaymentRepository.findByIdForUpdate("P9")).thenReturn(Optional.of(adv));
        when(supplierPaymentRepository.findByVendorIdAndAppliedFromAdvanceTrueAndReversedAtIsNull("V1"))
                .thenReturn(List.of(otherAdvancesRow, reversalRow));
        when(documentNumberService.next(DocumentKind.PAYMENT_VOUCHER, LocalDate.now())).thenReturn("PV-R");

        service.reverse("P9", "returned");

        verify(supplierPaymentRepository, never()).findByIdForUpdate("A1");
        verify(supplierPaymentRepository, never()).findByIdForUpdate("A2");
        assertThat(adv.getReversedAt()).isNotNull();
        ArgumentCaptor<SupplierLedgerPosting> ledger = ArgumentCaptor.forClass(SupplierLedgerPosting.class);
        verify(supplierLedgerService).post(ledger.capture());
        // the original is not found by id, so the narration falls back to its id
        assertThat(ledger.getValue().narration()).isEqualTo("Payment PV-R reverses P9");
        assertThat(ledger.getValue().entryType()).isEqualTo(SupplierLedgerEntryType.ADVANCE_PAID);
    }

    // ---------------------------------------------------------------- reads

    @Test
    void getAndListsMapEveryRow() {
        SupplierPayment p = advance("P1", "10.00");
        when(supplierPaymentRepository.findById("P1")).thenReturn(Optional.of(p));
        when(supplierPaymentRepository.findById("P0")).thenReturn(Optional.empty());
        when(supplierPaymentRepository.findBySupplierInvoiceIdOrderByPaidOnAscCreatedAtAsc("SI1")).thenReturn(List.of(p));

        assertThat(service.get("P1").getVoucherNumber()).isEqualTo("PV-P1");
        assertThatThrownBy(() -> service.get("P0"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Payment not found: P0");
        assertThat(service.listForInvoice("SI1")).extracting(SupplierPaymentResponse::getId).containsExactly("P1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void listForVendorFiltersOnTheVendorNewestFirst() {
        SupplierPayment p = advance("P1", "10.00");
        ArgumentCaptor<Specification<SupplierPayment>> spec = ArgumentCaptor.forClass(Specification.class);
        when(supplierPaymentRepository.findAll(spec.capture(), eq(Sort.by(Sort.Direction.DESC, "paidOn")))).thenReturn(List.of(p));

        assertThat(service.listForVendor("V1")).extracting(SupplierPaymentResponse::getId).containsExactly("P1");

        Root<SupplierPayment> root = mock(Root.class);
        Path<Object> path = mock(Path.class);
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        Predicate predicate = mock(Predicate.class);
        when(root.get("vendorId")).thenReturn(path);
        when(cb.equal(path, "V1")).thenReturn(predicate);
        assertThat(spec.getValue().toPredicate(root, mock(CriteriaQuery.class), cb)).isSameAs(predicate);
    }

    @Test
    void unallocatedAdvancesListsOnlyLiveAdvancesAndThePool() {
        when(vendorRepository.findById("V1")).thenReturn(Optional.of(vendor()));
        SupplierPayment live = advance("D1", "500.00");
        SupplierPayment reversed = advance("D2", "200.00");
        reversed.setReversedAt(LocalDateTime.now());
        SupplierPayment reversal = advance("D3", "-200.00");
        reversal.setReversesPaymentId("D2");
        when(supplierPaymentRepository.findByVendorIdAndIsAdvanceTrueOrderByPaidOnAsc("V1")).thenReturn(List.of(live, reversed, reversal));
        when(supplierPaymentRepository.findByVendorIdAndAppliedFromAdvanceTrueOrderByPaidOnAsc("V1")).thenReturn(List.of(advance("A1", "120.00")));

        SupplierUnallocatedAdvancesResponse out = service.unallocatedAdvances("V1");

        assertThat(out.getVendorName()).isEqualTo("Novotel Goa");
        assertThat(out.getRemainingAmount()).isEqualByComparingTo("380.00");
        assertThat(out.getAdvances()).hasSize(1);
        assertThat(out.getAdvances().get(0).getVoucherNumber()).isEqualTo("PV-D1");
        assertThat(out.getAdvances().get(0).getAmountInr()).isEqualByComparingTo("500.00");
    }

    @Test
    void theAdvicePdfRendersForABillPaymentAndAnAdvance() {
        Tenant agency = Tenant.builder().id("T1").agencyName("Test Agency").baseCurrencyCode("INR").build();
        when(tenantRepository.findById("T1")).thenReturn(Optional.of(agency));
        SupplierPayment onBill = advance("P1", "400.00");
        onBill.setIsAdvance(false);
        onBill.setSupplierInvoiceId("SI1");
        SupplierInvoice b = bill("SI1", "1000.00");
        when(supplierPaymentRepository.findById("P1")).thenReturn(Optional.of(onBill));
        when(supplierInvoiceRepository.findById("SI1")).thenReturn(Optional.of(b));
        when(supplierPaymentRepository.findBySupplierInvoiceIdOrderByPaidOnAscCreatedAtAsc("SI1")).thenReturn(List.of(onBill));
        when(vendorRepository.findById("V1")).thenReturn(Optional.of(vendor()), Optional.empty());
        SupplierPayment adv = advance("P2", "50.00");
        when(supplierPaymentRepository.findById("P2")).thenReturn(Optional.of(adv));

        byte[] billPdf = service.getAdvicePdf("P1");
        byte[] advancePdf = service.getAdvicePdf("P2");

        assertThat(new String(billPdf, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");
        assertThat(new String(advancePdf, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");
        verify(supplierPaymentRepository, org.mockito.Mockito.times(1)).findBySupplierInvoiceIdOrderByPaidOnAscCreatedAtAsc(any());
    }

    @Test
    void theAdvicePdfNeedsTheBillAndTheAgency() {
        SupplierPayment onBill = advance("P1", "400.00");
        onBill.setSupplierInvoiceId("SI9");
        when(supplierPaymentRepository.findById("P1")).thenReturn(Optional.of(onBill));
        when(supplierInvoiceRepository.findById("SI9")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getAdvicePdf("P1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Supplier bill not found: SI9");

        SupplierPayment adv = advance("P2", "50.00");
        when(supplierPaymentRepository.findById("P2")).thenReturn(Optional.of(adv));
        when(tenantRepository.findById("T1")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getAdvicePdf("P2"))
                .isInstanceOf(IllegalStateException.class).hasMessage("Agency not found: T1");
    }
}
