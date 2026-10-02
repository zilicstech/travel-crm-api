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
import com.voyra.crm.models.JournalPosting;
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

    @Test
    void payingAgainstABillPostsTheSettledJournal() {
        when(vendorRepository.findById("V1")).thenReturn(Optional.of(vendor()));
        SupplierInvoice invoice = approvedBill(new BigDecimal("1000.00"));
        when(supplierInvoiceRepository.findById("SI1")).thenReturn(Optional.of(invoice));
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

    @Test
    void applyingAnAdvancePostsTheAdvanceAppliedJournalWithNoSubsidiaryLedgerPost() {
        SupplierInvoice invoice = approvedBill(new BigDecimal("1000.00"));
        SupplierPayment advance = SupplierPayment.builder().id("D1").vendorId("V1").vendorName("Novotel Goa")
                .direction(SupplierPaymentDirection.PAYMENT).isAdvance(true)
                .amount(new BigDecimal("50000.00")).amountInr(new BigDecimal("50000.00")).build();
        when(supplierInvoiceRepository.findById("SI1")).thenReturn(Optional.of(invoice));
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

        when(supplierPaymentRepository.findById("P1")).thenReturn(Optional.of(original));
        when(supplierPaymentRepository.existsById(any())).thenReturn(false);
        when(documentNumberService.next(DocumentKind.PAYMENT_VOUCHER, LocalDate.now())).thenReturn("PAY/2026-27/0003");
        when(supplierInvoiceRepository.findById("SI1")).thenReturn(Optional.of(invoice));
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
}
