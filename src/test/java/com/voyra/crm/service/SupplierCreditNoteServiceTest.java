package com.voyra.crm.service;

import com.voyra.crm.entity.SupplierCreditNote;
import com.voyra.crm.entity.SupplierInvoice;
import com.voyra.crm.enums.ItcEligibility;
import com.voyra.crm.enums.SupplierCreditNoteReason;
import com.voyra.crm.enums.SupplierCreditNoteStatus;
import com.voyra.crm.enums.SupplierInvoiceStatus;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.dto.SupplierCreditNoteRequest;
import com.voyra.crm.models.JournalPosting;
import com.voyra.crm.repository.JournalEntryRepository;
import com.voyra.crm.repository.JournalLineRepository;
import com.voyra.crm.repository.SupplierCreditNoteRepository;
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
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Journal-content coverage for postCreditNoteJournal (posting-rule-table row 14) - the one GL
 * posting site the GL cleanup pass flagged as wired but untested.
 */
@ExtendWith(MockitoExtension.class)
class SupplierCreditNoteServiceTest {

    @Mock
    private SupplierCreditNoteRepository supplierCreditNoteRepository;
    @Mock
    private SupplierInvoiceRepository supplierInvoiceRepository;
    @Mock
    private SupplierPaymentRepository supplierPaymentRepository;
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
    private JournalLineRepository journalLineRepository;

    @InjectMocks
    private SupplierCreditNoteService supplierCreditNoteService;

    @BeforeEach
    void authenticateAsOwner() {
        CustomUserPrincipal principal = new CustomUserPrincipal("O1", "owner", UserType.AGENCY_OWNER, "T1");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private SupplierInvoice approvedBill(String itcEligibility) {
        return SupplierInvoice.builder()
                .id("SI1").vendorId("V1").vendorName("Ethiopian Airlines")
                .supplierInvoiceNumber("ETH-001")
                .status(SupplierInvoiceStatus.APPROVED)
                .itcEligibility(ItcEligibility.valueOf(itcEligibility))
                .currencyCode("INR").fxRateToInr(BigDecimal.ONE)
                .grandTotal(new BigDecimal("5250.00"))
                .amountPaid(BigDecimal.ZERO).creditNoteTotal(BigDecimal.ZERO)
                .build();
    }

    private SupplierCreditNoteRequest request(BigDecimal taxable, BigDecimal cgst, BigDecimal sgst) {
        SupplierCreditNoteRequest req = new SupplierCreditNoteRequest();
        req.setSupplierInvoiceId("SI1");
        req.setSupplierNoteNumber("CN-001");
        req.setReason(SupplierCreditNoteReason.RATE_CORRECTION);
        req.setTaxableValue(taxable);
        req.setCgstAmount(cgst);
        req.setSgstAmount(sgst);
        req.setIgstAmount(BigDecimal.ZERO);
        return req;
    }

    @Test
    void recordingACreditNoteAgainstAnItcEligibleBillReversesPurchaseAndInputGst() {
        SupplierInvoice invoice = approvedBill("ELIGIBLE");
        when(supplierInvoiceRepository.findById("SI1")).thenReturn(Optional.of(invoice));
        when(supplierCreditNoteRepository.findBySupplierInvoiceIdAndStatus("SI1", SupplierCreditNoteStatus.RECORDED))
                .thenReturn(List.of());
        when(journalEntryRepository.findBySourceTypeAndSourceIdAndPurpose(
                com.voyra.crm.enums.JournalSourceType.SUPPLIER_INVOICE, "SI1",
                com.voyra.crm.enums.JournalPurpose.SUPPLIER_BILL_BOOKED))
                .thenReturn(Optional.empty());

        supplierCreditNoteService.record(request(new BigDecimal("1000.00"), new BigDecimal("50.00"), new BigDecimal("50.00")));

        ArgumentCaptor<JournalPosting> captor = ArgumentCaptor.forClass(JournalPosting.class);
        verify(journalService).post(captor.capture());
        JournalPosting posting = captor.getValue();

        assertThat(posting.purpose()).isEqualTo(com.voyra.crm.enums.JournalPurpose.SUPPLIER_CREDIT_NOTE_RECEIVED);
        assertThat(posting.lines()).hasSize(3);
        assertThat(posting.lines().get(0).accountCode()).isEqualTo("2200"); // ACCOUNTS_PAYABLE
        assertThat(posting.lines().get(0).debitAmount()).isEqualByComparingTo("1100.00");
        assertThat(posting.lines().get(1).accountCode()).isEqualTo("5080"); // no prior journal found -> MISCELLANEOUS fallback
        assertThat(posting.lines().get(1).creditAmount()).isEqualByComparingTo("1000.00");
        assertThat(posting.lines().get(2).accountCode()).isEqualTo("1400"); // INPUT_GST_RECEIVABLE
        assertThat(posting.lines().get(2).creditAmount()).isEqualByComparingTo("100.00");

        BigDecimal totalDebit = posting.lines().stream().map(l -> l.debitAmount()).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCredit = posting.lines().stream().map(l -> l.creditAmount()).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(totalDebit).isEqualByComparingTo(totalCredit);
    }

    @Test
    void recordingACreditNoteAgainstAnIneligibleBillFoldsGstIntoThePurchaseCredit() {
        SupplierInvoice invoice = approvedBill("INELIGIBLE");
        when(supplierInvoiceRepository.findById("SI1")).thenReturn(Optional.of(invoice));
        when(supplierCreditNoteRepository.findBySupplierInvoiceIdAndStatus("SI1", SupplierCreditNoteStatus.RECORDED))
                .thenReturn(List.of());
        when(journalEntryRepository.findBySourceTypeAndSourceIdAndPurpose(
                com.voyra.crm.enums.JournalSourceType.SUPPLIER_INVOICE, "SI1",
                com.voyra.crm.enums.JournalPurpose.SUPPLIER_BILL_BOOKED))
                .thenReturn(Optional.empty());

        supplierCreditNoteService.record(request(new BigDecimal("2000.00"), new BigDecimal("50.00"), new BigDecimal("50.00")));

        ArgumentCaptor<JournalPosting> captor = ArgumentCaptor.forClass(JournalPosting.class);
        verify(journalService).post(captor.capture());
        JournalPosting posting = captor.getValue();

        assertThat(posting.lines()).hasSize(2); // no separate 1400 line - GST folded into the purchase credit
        assertThat(posting.lines().get(0).debitAmount()).isEqualByComparingTo("2100.00");
        assertThat(posting.lines().get(1).accountCode()).isEqualTo("5080");
        assertThat(posting.lines().get(1).creditAmount()).isEqualByComparingTo("2100.00"); // 2000 + 100 folded in
    }

    @Test
    void cancellingACreditNoteReversesItsJournalEntry() {
        SupplierCreditNote note = SupplierCreditNote.builder()
                .id("CN1").vendorId("V1").supplierInvoiceId("SI1").supplierNoteNumber("CN-001")
                .status(SupplierCreditNoteStatus.RECORDED)
                .currencyCode("INR").fxRateToInr(BigDecimal.ONE)
                .totalAmount(new BigDecimal("1100.00")).totalAmountInr(new BigDecimal("1100.00"))
                .refundedAmount(BigDecimal.ZERO)
                .build();
        SupplierInvoice invoice = approvedBill("ELIGIBLE");
        invoice.setCreditNoteTotal(new BigDecimal("1100.00"));
        when(supplierCreditNoteRepository.findById("CN1")).thenReturn(Optional.of(note));
        when(supplierInvoiceRepository.findById("SI1")).thenReturn(Optional.of(invoice));

        com.voyra.crm.entity.JournalEntry entry = com.voyra.crm.entity.JournalEntry.builder().id("JE1").build();
        when(journalEntryRepository.findBySourceTypeAndSourceIdAndPurpose(
                com.voyra.crm.enums.JournalSourceType.SUPPLIER_CREDIT_NOTE, "CN1",
                com.voyra.crm.enums.JournalPurpose.SUPPLIER_CREDIT_NOTE_RECEIVED))
                .thenReturn(Optional.of(entry));

        supplierCreditNoteService.cancel("CN1", "Entered in error");

        verify(journalService).reverse("JE1", "Supplier credit note cancelled: Entered in error");
        assertThat(note.getStatus()).isEqualTo(SupplierCreditNoteStatus.CANCELLED);
    }
}
