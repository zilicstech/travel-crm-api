package com.voyra.crm.service;

import com.voyra.crm.dto.SupplierCreditNoteRefundRequest;
import com.voyra.crm.dto.SupplierCreditNoteRequest;
import com.voyra.crm.dto.SupplierCreditNoteResponse;
import com.voyra.crm.entity.JournalEntry;
import com.voyra.crm.entity.JournalLine;
import com.voyra.crm.entity.SupplierCreditNote;
import com.voyra.crm.entity.SupplierInvoice;
import com.voyra.crm.enums.InvoiceServiceCategory;
import com.voyra.crm.enums.ItcEligibility;
import com.voyra.crm.enums.JournalPurpose;
import com.voyra.crm.enums.JournalSourceType;
import com.voyra.crm.enums.PaymentMode;
import com.voyra.crm.enums.SupplierCreditNoteReason;
import com.voyra.crm.enums.SupplierCreditNoteStatus;
import com.voyra.crm.enums.SupplierInvoiceStatus;
import com.voyra.crm.enums.SystemAccount;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.exception.BookingConflictException;
import com.voyra.crm.models.JournalLinePosting;
import com.voyra.crm.models.JournalPosting;
import com.voyra.crm.models.SupplierLedgerPosting;
import com.voyra.crm.repository.BookingRepository;
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
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Branch coverage for SupplierCreditNoteService. */
@ExtendWith(MockitoExtension.class)
class SupplierCreditNoteServiceCoverageTest {

    @Mock SupplierCreditNoteRepository supplierCreditNoteRepository;
    @Mock SupplierInvoiceRepository supplierInvoiceRepository;
    @Mock SupplierPaymentRepository supplierPaymentRepository;
    @Mock DocumentNumberService documentNumberService;
    @Mock SupplierLedgerService supplierLedgerService;
    @Mock AuditService auditService;
    @Mock JournalService journalService;
    @Mock JournalEntryRepository journalEntryRepository;
    @Mock JournalLineRepository journalLineRepository;
    @Mock BookingRepository bookingRepository;
    @Mock LedgerAccountResolver ledgerAccountResolver;
    @InjectMocks SupplierCreditNoteService service;

    @BeforeEach
    void signIn() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal("O1", "owner", UserType.AGENCY_OWNER, "T1"), null, List.of()));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static SupplierInvoice bill(SupplierInvoiceStatus status) {
        return SupplierInvoice.builder().id("SI1").vendorId("V1").vendorName("Agoda").supplierInvoiceNumber("AG-1")
                .status(status).itcEligibility(ItcEligibility.INELIGIBLE).currencyCode("INR").fxRateToInr(BigDecimal.ONE)
                .grandTotal(new BigDecimal("1000.00")).amountPaid(BigDecimal.ZERO).creditNoteTotal(BigDecimal.ZERO).build();
    }

    private static SupplierCreditNoteRequest req(String number, String taxable) {
        SupplierCreditNoteRequest r = new SupplierCreditNoteRequest();
        r.setSupplierInvoiceId("SI1");
        r.setSupplierNoteNumber(number);
        r.setReason(SupplierCreditNoteReason.BILLING_ERROR);
        r.setTaxableValue(new BigDecimal(taxable));
        r.setCgstAmount(BigDecimal.ZERO);
        r.setSgstAmount(BigDecimal.ZERO);
        r.setIgstAmount(BigDecimal.ZERO);
        return r;
    }

    private static SupplierCreditNote note(SupplierCreditNoteStatus status) {
        return SupplierCreditNote.builder().id("SCN1").vendorId("V1").vendorName("Agoda").supplierInvoiceId("SI1")
                .status(status).totalAmount(new BigDecimal("100.00")).totalAmountInr(new BigDecimal("100.00"))
                .refundableAmount(new BigDecimal("100.00")).build();
    }

    private JournalPosting posted() {
        ArgumentCaptor<JournalPosting> j = ArgumentCaptor.forClass(JournalPosting.class);
        verify(journalService).post(j.capture());
        return j.getValue();
    }

    @Test
    void onlyAnApprovedPartlyPaidOrPaidBillTakesACreditNote() {
        when(supplierInvoiceRepository.findById("SI1")).thenReturn(Optional.of(bill(SupplierInvoiceStatus.DRAFT)));
        when(supplierInvoiceRepository.findById("SI9")).thenReturn(Optional.empty());
        SupplierCreditNoteRequest unknown = req(null, "10");
        unknown.setSupplierInvoiceId("SI9");

        assertThatThrownBy(() -> service.record(req(null, "10"))).hasMessage("Only an approved bill can carry a credit note");
        assertThatThrownBy(() -> service.record(unknown)).hasMessage("Supplier bill not found: SI9");
        verify(supplierCreditNoteRepository, never()).save(any());
    }

    @Test
    void aPlatformBookingsBillIsManagedByVoyra() {
        SupplierInvoice mirrored = bill(SupplierInvoiceStatus.APPROVED);
        mirrored.setBookingId("B1");
        when(supplierInvoiceRepository.findById("SI1")).thenReturn(Optional.of(mirrored));
        when(bookingRepository.existsByIdAndPlatformBookingIdIsNotNull("B1")).thenReturn(true);

        assertThatThrownBy(() -> service.record(req(null, "10"))).isInstanceOf(BookingConflictException.class);
    }

    @Test
    void theSameSupplierNumberIsNotRecordedTwice() {
        when(supplierInvoiceRepository.findById("SI1")).thenReturn(Optional.of(bill(SupplierInvoiceStatus.PAID)));
        when(supplierCreditNoteRepository.existsByVendorIdAndSupplierNoteNumberAndStatusNot("V1", "CN-1", SupplierCreditNoteStatus.CANCELLED))
                .thenReturn(true);

        assertThatThrownBy(() -> service.record(req(" CN-1 ", "10"))).hasMessage("Credit note CN-1 from this supplier is already recorded");
    }

    @Test
    void aCreditBeyondTheBillsGrandTotalIsRefused() {
        when(supplierInvoiceRepository.findById("SI1")).thenReturn(Optional.of(bill(SupplierInvoiceStatus.APPROVED)));
        SupplierCreditNote earlier = note(SupplierCreditNoteStatus.RECORDED);
        earlier.setTotalAmount(new BigDecimal("950.00"));
        when(supplierCreditNoteRepository.findBySupplierInvoiceIdAndStatus("SI1", SupplierCreditNoteStatus.RECORDED)).thenReturn(List.of(earlier));

        assertThatThrownBy(() -> service.record(req(" ", "100"))).hasMessage("This credit note would credit more than the bill's grand total");
    }

    @Test
    void anUnnumberedNoteOnAPartlyPaidBillCreditsTheOriginalPurchaseAccountAndNamesTheBillByDate() {
        SupplierInvoice b = bill(SupplierInvoiceStatus.PARTIALLY_PAID);
        b.setSupplierInvoiceNumber(null);
        b.setInvoiceDate(LocalDate.of(2026, 9, 1));
        b.setAmountPaid(new BigDecimal("900.00"));
        when(supplierInvoiceRepository.findById("SI1")).thenReturn(Optional.of(b));
        when(journalEntryRepository.findBySourceTypeAndSourceIdAndPurpose(JournalSourceType.SUPPLIER_INVOICE, "SI1", JournalPurpose.SUPPLIER_BILL_BOOKED))
                .thenReturn(Optional.of(JournalEntry.builder().id("JE1").build()));
        when(journalLineRepository.findByJournalEntryIdOrderByLineNo("JE1")).thenReturn(List.of(
                JournalLine.builder().accountCode(SystemAccount.ACCOUNTS_PAYABLE.code()).debitAmount(BigDecimal.ZERO).build(),
                JournalLine.builder().accountCode(SystemAccount.INPUT_GST_RECEIVABLE.code()).debitAmount(BigDecimal.TEN).build(),
                JournalLine.builder().accountCode(SystemAccount.TDS_RECEIVABLE.code()).debitAmount(BigDecimal.TEN).build(),
                JournalLine.builder().accountCode("5030").debitAmount(new BigDecimal("1000")).build()));
        SupplierCreditNoteRequest r = req(null, "300.00");
        r.setRetentionFee(new BigDecimal("50.00"));
        r.setNoteDate(LocalDate.of(2026, 10, 1));

        SupplierCreditNoteResponse res = service.record(r);

        assertThat(res.getRetentionFee()).isEqualByComparingTo("50.00");
        // owed 1000 - 900 = 100, so 200 of the 300 credit is refundable cash
        assertThat(res.getRefundableAmount()).isEqualByComparingTo("200.00");
        assertThat(b.getBalanceDue()).isEqualByComparingTo("-200.00");
        JournalPosting j = posted();
        assertThat(j.lines()).extracting(JournalLinePosting::accountCode).containsExactly(SystemAccount.ACCOUNTS_PAYABLE.code(), "5030");
        assertThat(j.narration()).isEqualTo("Supplier credit note (no number) against Agoda bill of 2026-09-01");
        assertThat(j.entryDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        ArgumentCaptor<SupplierLedgerPosting> ledger = ArgumentCaptor.forClass(SupplierLedgerPosting.class);
        verify(supplierLedgerService).post(ledger.capture());
        assertThat(ledger.getValue().narration()).isEqualTo("Supplier credit note (no number) against Agoda bill of 2026-09-01");
    }

    @Test
    void aZeroNoteDatedTodayPostsNothing() {
        when(supplierInvoiceRepository.findById("SI1")).thenReturn(Optional.of(bill(SupplierInvoiceStatus.APPROVED)));
        when(journalEntryRepository.findBySourceTypeAndSourceIdAndPurpose(JournalSourceType.SUPPLIER_INVOICE, "SI1", JournalPurpose.SUPPLIER_BILL_BOOKED))
                .thenReturn(Optional.empty());

        SupplierCreditNoteResponse res = service.record(req("CN-2", "0"));

        assertThat(res.getNoteDate()).isEqualTo(LocalDate.now());
        verify(journalService, never()).post(any());
    }

    @Test
    void anEligibleBillsGstOnlyNoteReversesInputGstAlone() {
        SupplierInvoice b = bill(SupplierInvoiceStatus.APPROVED);
        b.setItcEligibility(ItcEligibility.ELIGIBLE);
        when(supplierInvoiceRepository.findById("SI1")).thenReturn(Optional.of(b));
        when(journalEntryRepository.findBySourceTypeAndSourceIdAndPurpose(JournalSourceType.SUPPLIER_INVOICE, "SI1", JournalPurpose.SUPPLIER_BILL_BOOKED))
                .thenReturn(Optional.empty());
        SupplierCreditNoteRequest r = req("CN-3", "0");
        r.setIgstAmount(new BigDecimal("18.00"));

        service.record(r);

        JournalPosting j = posted();
        assertThat(j.lines()).extracting(JournalLinePosting::accountCode)
                .containsExactly(SystemAccount.ACCOUNTS_PAYABLE.code(), SystemAccount.INPUT_GST_RECEIVABLE.code());
    }

    @Test
    void anOriginalJournalWithNoExpenseLineFallsBackToMiscellaneousPurchases() {
        when(supplierInvoiceRepository.findById("SI1")).thenReturn(Optional.of(bill(SupplierInvoiceStatus.APPROVED)));
        when(journalEntryRepository.findBySourceTypeAndSourceIdAndPurpose(JournalSourceType.SUPPLIER_INVOICE, "SI1", JournalPurpose.SUPPLIER_BILL_BOOKED))
                .thenReturn(Optional.of(JournalEntry.builder().id("JE1").build()));
        when(journalLineRepository.findByJournalEntryIdOrderByLineNo("JE1")).thenReturn(List.of(
                JournalLine.builder().accountCode(SystemAccount.INPUT_GST_RECEIVABLE.code()).debitAmount(BigDecimal.TEN).build()));

        service.record(req("CN-4", "100"));

        assertThat(posted().lines().get(1).accountCode()).isEqualTo(SystemAccount.purchaseCode(InvoiceServiceCategory.MISCELLANEOUS));
    }

    @Test
    void theDefaultPurchaseAccountIsMiscellaneous() {
        SupplierInvoice ownBooking = bill(SupplierInvoiceStatus.APPROVED);
        ownBooking.setBookingId("B1");
        when(supplierInvoiceRepository.findById("SI1")).thenReturn(Optional.of(ownBooking));
        when(bookingRepository.existsByIdAndPlatformBookingIdIsNotNull("B1")).thenReturn(false);
        when(journalEntryRepository.findBySourceTypeAndSourceIdAndPurpose(JournalSourceType.SUPPLIER_INVOICE, "SI1", JournalPurpose.SUPPLIER_BILL_BOOKED))
                .thenReturn(Optional.empty());

        service.record(req("CN-5", "100"));

        assertThat(posted().lines().get(1).accountCode()).isEqualTo(SystemAccount.purchaseCode(InvoiceServiceCategory.MISCELLANEOUS));
    }

    @Test
    void aCancelledOrRefundedNoteCannotBeCancelled() {
        SupplierCreditNote cancelled = note(SupplierCreditNoteStatus.CANCELLED);
        SupplierCreditNote refunded = note(SupplierCreditNoteStatus.RECORDED);
        refunded.setId("SCN2");
        refunded.setRefundedAmount(BigDecimal.ONE);
        when(supplierCreditNoteRepository.findById("SCN1")).thenReturn(Optional.of(cancelled));
        when(supplierCreditNoteRepository.findById("SCN2")).thenReturn(Optional.of(refunded));
        when(supplierCreditNoteRepository.findById("SCN9")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancel("SCN1", "x")).hasMessage("This credit note is already cancelled");
        assertThatThrownBy(() -> service.cancel("SCN2", "x")).hasMessage("This credit note has refunds against it and can no longer be cancelled");
        assertThatThrownBy(() -> service.cancel("SCN9", "x")).hasMessage("Supplier credit note not found: SCN9");
    }

    @Test
    void cancellingANoteWithNoJournalRestoresTheBill() {
        SupplierCreditNote n = note(SupplierCreditNoteStatus.RECORDED);
        when(supplierCreditNoteRepository.findById("SCN1")).thenReturn(Optional.of(n));
        SupplierInvoice b = bill(SupplierInvoiceStatus.APPROVED);
        b.setCreditNoteTotal(new BigDecimal("100.00"));
        when(supplierInvoiceRepository.findById("SI1")).thenReturn(Optional.of(b));
        when(journalEntryRepository.findBySourceTypeAndSourceIdAndPurpose(JournalSourceType.SUPPLIER_CREDIT_NOTE, "SCN1", JournalPurpose.SUPPLIER_CREDIT_NOTE_RECEIVED))
                .thenReturn(Optional.empty());

        SupplierCreditNoteResponse res = service.cancel("SCN1", "duplicate");

        assertThat(res.getStatus()).isEqualTo(SupplierCreditNoteStatus.CANCELLED);
        assertThat(b.getCreditNoteTotal()).isEqualByComparingTo("0");
        assertThat(b.getBalanceDue()).isEqualByComparingTo("1000.00");
        verify(journalService, never()).reverse(any(), any());
    }

    private SupplierCreditNoteRefundRequest refund(String amount, PaymentMode mode) {
        SupplierCreditNoteRefundRequest r = new SupplierCreditNoteRefundRequest();
        r.setAmount(new BigDecimal(amount));
        r.setPaymentMode(mode);
        r.setReceivedOn(LocalDate.of(2026, 10, 9));
        return r;
    }

    @Test
    void onlyARecordedNoteWithRefundableBalanceIsRefunded() {
        SupplierCreditNote cancelled = note(SupplierCreditNoteStatus.CANCELLED);
        when(supplierCreditNoteRepository.findById("SCN1")).thenReturn(Optional.of(cancelled), Optional.of(note(SupplierCreditNoteStatus.RECORDED)));

        assertThatThrownBy(() -> service.receiveRefund("SCN1", refund("10", PaymentMode.CASH))).hasMessage("Only a recorded credit note can be refunded");
        assertThatThrownBy(() -> service.receiveRefund("SCN1", refund("100.01", PaymentMode.CASH)))
                .hasMessage("Refund amount exceeds the remaining refundable balance of 100.00");
        verify(supplierPaymentRepository, never()).save(any());
    }

    @Test
    void aCashRefundDebitsCashAndAWalletRefundDebitsSupplierAdvances() {
        SupplierCreditNote n = note(SupplierCreditNoteStatus.RECORDED);
        n.setSupplierNoteNumber("CN-7");
        when(supplierCreditNoteRepository.findById("SCN1")).thenReturn(Optional.of(n));
        when(supplierInvoiceRepository.findById("SI1")).thenReturn(Optional.of(bill(SupplierInvoiceStatus.PAID)));

        service.receiveRefund("SCN1", refund("40.00", PaymentMode.CASH));
        service.receiveRefund("SCN1", refund("10.00", PaymentMode.UPI), true);

        ArgumentCaptor<JournalPosting> j = ArgumentCaptor.forClass(JournalPosting.class);
        verify(journalService, org.mockito.Mockito.times(2)).post(j.capture());
        assertThat(j.getAllValues().get(0).lines().get(0).accountCode()).isEqualTo(SystemAccount.CASH_IN_HAND.code());
        assertThat(j.getAllValues().get(0).narration()).isEqualTo("Refund received from Agoda - credited to the agency's bank (credit note CN-7)");
        assertThat(j.getAllValues().get(1).lines().get(0).accountCode()).isEqualTo(SystemAccount.SUPPLIER_ADVANCES.code());
        assertThat(j.getAllValues().get(1).narration()).isEqualTo("Refund received from Agoda - kept in the supplier wallet (credit note CN-7)");
        assertThat(n.getRefundedAmount()).isEqualByComparingTo("50.00");
    }

    @Test
    void aZeroRefundPostsNoJournal() {
        when(supplierCreditNoteRepository.findById("SCN1")).thenReturn(Optional.of(note(SupplierCreditNoteStatus.RECORDED)));
        when(supplierInvoiceRepository.findById("SI1")).thenReturn(Optional.of(bill(SupplierInvoiceStatus.PAID)));

        service.receiveRefund("SCN1", refund("0", PaymentMode.BANK_TRANSFER));

        verify(journalService, never()).post(any());
        ArgumentCaptor<SupplierLedgerPosting> ledger = ArgumentCaptor.forClass(SupplierLedgerPosting.class);
        verify(supplierLedgerService).post(ledger.capture());
        assertThat(ledger.getValue().narration()).isEqualTo("Refund received from Agoda - credited to the agency's bank");
    }

    @Test
    void notesAreReadOneOrByVendor() {
        when(supplierCreditNoteRepository.findById("SCN1")).thenReturn(Optional.of(note(SupplierCreditNoteStatus.RECORDED)));
        when(supplierCreditNoteRepository.findByVendorIdOrderByNoteDateDesc("V1")).thenReturn(List.of(note(SupplierCreditNoteStatus.CANCELLED)));

        assertThat(service.get("SCN1").getStatus()).isEqualTo(SupplierCreditNoteStatus.RECORDED);
        assertThat(service.listForVendor("V1")).singleElement().satisfies(r -> assertThat(r.getStatus()).isEqualTo(SupplierCreditNoteStatus.CANCELLED));
    }
}
