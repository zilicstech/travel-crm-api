package com.voyra.crm.service;

import com.voyra.crm.dto.CreditNoteRefundRequest;
import com.voyra.crm.dto.CreditNoteRequest;
import com.voyra.crm.dto.CreditNoteResponse;
import com.voyra.crm.dto.PagedResponse;
import com.voyra.crm.entity.Booking;
import com.voyra.crm.entity.CreditNote;
import com.voyra.crm.entity.Invoice;
import com.voyra.crm.entity.InvoiceLineItem;
import com.voyra.crm.entity.JournalEntry;
import com.voyra.crm.entity.JournalLine;
import com.voyra.crm.entity.PaymentReceipt;
import com.voyra.crm.enums.BookingStatus;
import com.voyra.crm.enums.CreditNoteReason;
import com.voyra.crm.enums.CreditNoteStatus;
import com.voyra.crm.enums.DocumentKind;
import com.voyra.crm.enums.InvoiceBillingModel;
import com.voyra.crm.enums.InvoiceDocumentType;
import com.voyra.crm.enums.InvoiceLifecycle;
import com.voyra.crm.enums.InvoiceServiceCategory;
import com.voyra.crm.enums.JournalPurpose;
import com.voyra.crm.enums.JournalSourceType;
import com.voyra.crm.enums.PaymentMode;
import com.voyra.crm.enums.ReceiptDirection;
import com.voyra.crm.enums.SystemAccount;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.models.JournalLinePosting;
import com.voyra.crm.models.JournalPosting;
import com.voyra.crm.models.LedgerPosting;
import com.voyra.crm.repository.BookingRepository;
import com.voyra.crm.repository.CreditNoteRepository;
import com.voyra.crm.repository.InvoiceLineItemRepository;
import com.voyra.crm.repository.InvoiceRepository;
import com.voyra.crm.repository.JournalEntryRepository;
import com.voyra.crm.repository.JournalLineRepository;
import com.voyra.crm.repository.PaymentReceiptRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Branch coverage for CreditNoteService. */
@ExtendWith(MockitoExtension.class)
class CreditNoteServiceCoverageTest {

    @Mock CreditNoteRepository creditNoteRepository;
    @Mock InvoiceRepository invoiceRepository;
    @Mock PaymentReceiptRepository paymentReceiptRepository;
    @Mock BookingRepository bookingRepository;
    @Mock DocumentNumberService documentNumberService;
    @Mock AuditService auditService;
    @Mock CustomerLedgerService customerLedgerService;
    @Mock BookingAccountingSync bookingAccountingSync;
    @Mock JournalService journalService;
    @Mock JournalEntryRepository journalEntryRepository;
    @Mock JournalLineRepository journalLineRepository;
    @Mock InvoiceLineItemRepository invoiceLineItemRepository;
    @InjectMocks CreditNoteService service;

    @BeforeEach
    void signIn() {
        signInAs(UserType.ACCOUNTANT);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void signInAs(UserType type) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal("AC1", "user", type, "T1"), null, List.of()));
    }

    private static Invoice invoice() {
        return Invoice.builder().id("I1").invoiceNumber("INV-1").documentType(InvoiceDocumentType.TAX_INVOICE)
                .status(InvoiceLifecycle.ISSUED).clientId("K1").clientName("Asha").bookingId("B1")
                .serviceCategory(InvoiceServiceCategory.HOTEL).currencyCode("INR").fxRateToInr(BigDecimal.ONE)
                .taxableValue(new BigDecimal("1000.00")).cgstAmount(new BigDecimal("25.00")).sgstAmount(new BigDecimal("25.00"))
                .igstAmount(BigDecimal.ZERO).tcsAmount(BigDecimal.ZERO)
                .grandTotal(new BigDecimal("1050.00")).grandTotalInr(new BigDecimal("1050.00"))
                .amountReceived(BigDecimal.ZERO).creditNoteTotal(BigDecimal.ZERO).balanceDue(new BigDecimal("1050.00")).build();
    }

    private static CreditNoteRequest request(CreditNoteReason reason) {
        CreditNoteRequest r = new CreditNoteRequest();
        r.setInvoiceId("I1");
        r.setReason(reason);
        return r;
    }

    private CreditNote saved() {
        ArgumentCaptor<CreditNote> c = ArgumentCaptor.forClass(CreditNote.class);
        verify(creditNoteRepository).save(c.capture());
        return c.getValue();
    }

    private static CreditNote draftNote(String total) {
        BigDecimal t = new BigDecimal(total);
        return CreditNote.builder().id("CN1").invoiceId("I1").clientId("K1").bookingId("B1").status(CreditNoteStatus.DRAFT)
                .currencyCode("INR").fxRateToInr(BigDecimal.ONE).taxableValue(t).totalAmount(t).totalAmountInr(t).build();
    }

    // ------------------------------------------------------------------ create

    @Test
    void createNeedsAnIssuedTaxInvoice() {
        when(invoiceRepository.findById("I9")).thenReturn(Optional.empty());
        CreditNoteRequest unknown = request(CreditNoteReason.GOODWILL);
        unknown.setInvoiceId("I9");
        assertThatThrownBy(() -> service.create(unknown)).hasMessage("Invoice not found: I9");

        Invoice proforma = invoice();
        proforma.setDocumentType(InvoiceDocumentType.PROFORMA);
        Invoice draft = invoice();
        draft.setStatus(InvoiceLifecycle.DRAFT);
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(proforma), Optional.of(draft));
        assertThatThrownBy(() -> service.create(request(CreditNoteReason.GOODWILL))).hasMessage("Only a tax invoice can carry a credit note");
        assertThatThrownBy(() -> service.create(request(CreditNoteReason.GOODWILL))).hasMessage("Only an issued invoice can be credited");
        verify(creditNoteRepository, never()).save(any());
    }

    @Test
    void aPaidInvoiceCanBeCreditedForAGoneBookingOnTheGivenDate() {
        Invoice paid = invoice();
        paid.setStatus(InvoiceLifecycle.PAID);
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(paid));
        when(bookingRepository.findById("B1")).thenReturn(Optional.empty());
        CreditNoteRequest r = request(CreditNoteReason.BOOKING_CANCELLED);
        r.setBookingId(" ");
        r.setNoteDate(LocalDate.of(2026, 10, 2));

        CreditNoteResponse res = service.create(r);

        CreditNote n = saved();
        assertThat(n.getBookingId()).isEqualTo("B1");
        assertThat(n.getCancellationFee()).isEqualByComparingTo("0");
        assertThat(n.getTaxableValue()).isEqualByComparingTo("1000.00");
        assertThat(n.getTotalAmount()).isEqualByComparingTo("1050.00");
        assertThat(n.getNoteDate()).isEqualTo(LocalDate.of(2026, 10, 2));
        assertThat(res.getStatus()).isEqualTo(CreditNoteStatus.DRAFT);
    }

    @Test
    void aPartlyPaidInvoiceWithoutABookingNeedsNoCancellationCheck() {
        Invoice partly = invoice();
        partly.setStatus(InvoiceLifecycle.PARTIALLY_PAID);
        partly.setBookingId(null);
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(partly));

        service.create(request(CreditNoteReason.BOOKING_CANCELLED));

        assertThat(saved().getBookingId()).isNull();
        verify(bookingRepository, never()).findById(any());
    }

    @Test
    void aCancelledBookingPassesTheCheckAndAnInvoiceWithNoTaxableValueCreditsNoTax() {
        Invoice zero = invoice();
        zero.setTaxableValue(BigDecimal.ZERO);
        zero.setGrandTotal(BigDecimal.ZERO);
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(zero));
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(Booking.builder().id("B1").bookingStatus(BookingStatus.CANCELLED).build()));

        service.create(request(CreditNoteReason.BOOKING_CANCELLED));

        CreditNote n = saved();
        assertThat(n.getCgstAmount()).isEqualByComparingTo("0");
        assertThat(n.getTotalAmount()).isEqualByComparingTo("0");
    }

    @Test
    void aBookingNotOnACombinedInvoiceCannotBeCredited() {
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(invoice()));
        when(invoiceLineItemRepository.findByInvoiceIdOrderBySortOrderAsc("I1")).thenReturn(List.of(
                InvoiceLineItem.builder().bookingId("B1").taxableValue(new BigDecimal("600")).build(),
                InvoiceLineItem.builder().bookingId("B2").taxableValue(new BigDecimal("400")).build()));
        CreditNoteRequest r = request(CreditNoteReason.GOODWILL);
        r.setBookingId("B9");

        assertThatThrownBy(() -> service.create(r)).hasMessage("That booking isn't on invoice INV-1");
    }

    @Test
    void aCommissionAgentInvoiceWithNoPostedJournalIsProRated() {
        Invoice ca = invoice();
        ca.setBillingModel(InvoiceBillingModel.COMMISSION_AGENT);
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(ca));
        when(journalEntryRepository.findBySourceTypeAndSourceIdAndPurpose(JournalSourceType.INVOICE, "I1", JournalPurpose.INVOICE_RAISED_COMMISSION_AGENT))
                .thenReturn(Optional.empty());
        CreditNoteRequest r = request(CreditNoteReason.GOODWILL);
        r.setCancellationFee(new BigDecimal("500.00"));
        r.setSupplierRetained(new BigDecimal("100.00"));

        service.create(r);

        // half the taxable credited: half the tax
        assertThat(saved().getCgstAmount()).isEqualByComparingTo("12.50");
    }

    @Test
    void aCommissionAgentInvoiceThatIsAllPassThroughRefundsWithoutTax() {
        Invoice ca = invoice();
        ca.setBillingModel(InvoiceBillingModel.COMMISSION_AGENT);
        ca.setFxRateToInr(null);
        when(journalEntryRepository.findBySourceTypeAndSourceIdAndPurpose(JournalSourceType.INVOICE, "I1", JournalPurpose.INVOICE_RAISED_COMMISSION_AGENT))
                .thenReturn(Optional.of(JournalEntry.builder().id("JE1").build()));
        when(journalLineRepository.findByJournalEntryIdOrderByLineNo("JE1")).thenReturn(List.of(
                JournalLine.builder().accountCode(SystemAccount.CLIENT_PASS_THROUGH_PAYABLE.code()).creditAmountInr(new BigDecimal("1000.00")).build(),
                JournalLine.builder().accountCode(SystemAccount.CLIENT_PASS_THROUGH_PAYABLE.code()).creditAmountInr(null).build()));
        when(paymentReceiptRepository.findByInvoiceIdOrderByReceivedOnAscCreatedAtAsc("I1")).thenReturn(List.of(
                PaymentReceipt.builder().direction(ReceiptDirection.RECEIPT).amount(new BigDecimal("1050.00")).build()));

        // No fee on the invoice to refund tax on: the whole taxable comes back, untaxed.
        assertThat(service.previewRefundable(ca, null, BigDecimal.ZERO, BigDecimal.ZERO)).isEqualByComparingTo("1000.00");
    }

    // ------------------------------------------------------------------ issue

    @Test
    void onlyADraftWithALiveInvoiceIsIssued() {
        CreditNote issued = draftNote("100");
        issued.setStatus(CreditNoteStatus.ISSUED);
        when(creditNoteRepository.findById("CN1")).thenReturn(Optional.of(issued));
        assertThatThrownBy(() -> service.issue("CN1")).hasMessage("Only a draft credit note can be issued");

        CreditNote orphan = draftNote("100");
        orphan.setId("CN2");
        when(creditNoteRepository.findById("CN2")).thenReturn(Optional.of(orphan));
        when(invoiceRepository.findById("I1")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.issue("CN2")).hasMessage("Invoice not found: I1");
        when(creditNoteRepository.findById("CN9")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.issue("CN9")).hasMessage("Credit note not found: CN9");
    }

    private void stubIssue(CreditNote note, Invoice inv) {
        when(creditNoteRepository.findById(note.getId())).thenReturn(Optional.of(note));
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(inv));
        when(documentNumberService.next(eq(DocumentKind.CREDIT_NOTE), any())).thenReturn("CN-0001");
    }

    @Test
    void aPartlyPaidInvoiceCreditedIsPartlyPaidAndTheRecognisedRevenueIsReversed() {
        CreditNote note = draftNote("200.00");
        Invoice inv = invoice();
        inv.setServiceCategory(null);
        stubIssue(note, inv);
        CreditNote earlier = draftNote("50.00");
        earlier.setId("CN0");
        when(creditNoteRepository.findByInvoiceIdAndStatus("I1", CreditNoteStatus.ISSUED)).thenReturn(List.of(note, earlier));
        when(paymentReceiptRepository.findByInvoiceIdOrderByReceivedOnAscCreatedAtAsc("I1")).thenReturn(List.of(
                PaymentReceipt.builder().direction(ReceiptDirection.RECEIPT).amount(new BigDecimal("500.00")).build(),
                PaymentReceipt.builder().direction(ReceiptDirection.REFUND).amount(new BigDecimal("100.00")).build()));
        when(journalEntryRepository.findBySourceTypeAndSourceIdAndPurpose(JournalSourceType.INVOICE, "I1", JournalPurpose.INVOICE_RAISED_DEFERRED))
                .thenReturn(Optional.empty());
        when(journalEntryRepository.findBySourceTypeAndSourceIdAndPurpose(JournalSourceType.INVOICE, "I1", JournalPurpose.INVOICE_RAISED_RECOGNIZED))
                .thenReturn(Optional.of(JournalEntry.builder().id("JE1").build()));

        CreditNoteResponse res = service.issue("CN1");

        // received 400 net; still billed 1050 - 50 - 200 = 800; nothing comes back
        assertThat(res.getRefundableAmount()).isEqualByComparingTo("0");
        assertThat(inv.getBalanceDue()).isEqualByComparingTo("850.00");
        assertThat(inv.getStatus()).isEqualTo(InvoiceLifecycle.PARTIALLY_PAID);
        ArgumentCaptor<JournalPosting> j = ArgumentCaptor.forClass(JournalPosting.class);
        verify(journalService).post(j.capture());
        assertThat(j.getValue().lines().get(0).accountCode()).isEqualTo(SystemAccount.salesCode(InvoiceServiceCategory.MISCELLANEOUS));
        assertThat(j.getValue().lines()).hasSize(2);
        assertThat(j.getValue().entryDate()).isEqualTo(LocalDate.now());
        verify(bookingAccountingSync).syncRefund("B1");
    }

    @Test
    void aFullyCreditedPaidInvoiceIsSettledAndATaxBelowZeroIsNotPosted() {
        CreditNote note = draftNote("1050.00");
        note.setTotalAmountInr(new BigDecimal("1000.00"));
        note.setNoteDate(LocalDate.of(2026, 10, 3));
        Invoice inv = invoice();
        inv.setAmountReceived(new BigDecimal("0"));
        stubIssue(note, inv);
        when(paymentReceiptRepository.findByInvoiceIdOrderByReceivedOnAscCreatedAtAsc("I1")).thenReturn(List.of(
                PaymentReceipt.builder().direction(ReceiptDirection.RECEIPT).amount(new BigDecimal("1050.00")).build()));
        when(journalEntryRepository.findBySourceTypeAndSourceIdAndPurpose(JournalSourceType.INVOICE, "I1", JournalPurpose.INVOICE_RAISED_DEFERRED))
                .thenReturn(Optional.of(JournalEntry.builder().id("JE1").build()));

        CreditNoteResponse res = service.issue("CN1");

        assertThat(res.getRefundableAmount()).isEqualByComparingTo("1050.00");
        assertThat(inv.getStatus()).isEqualTo(InvoiceLifecycle.PAID);
        ArgumentCaptor<JournalPosting> j = ArgumentCaptor.forClass(JournalPosting.class);
        verify(journalService).post(j.capture());
        assertThat(j.getValue().lines()).extracting(JournalLinePosting::accountCode).containsExactly(
                SystemAccount.UNEARNED_TOUR_REVENUE.code(), SystemAccount.ACCOUNTS_RECEIVABLE.code());
        assertThat(j.getValue().entryDate()).isEqualTo(LocalDate.of(2026, 10, 3));
    }

    @Test
    void aPartlyPaidInvoiceStaysPartlyPaidAfterACredit() {
        CreditNote note = draftNote("100.00");
        Invoice inv = invoice();
        inv.setAmountReceived(new BigDecimal("300.00"));
        stubIssue(note, inv);
        when(paymentReceiptRepository.findByInvoiceIdOrderByReceivedOnAscCreatedAtAsc("I1")).thenReturn(List.of(
                PaymentReceipt.builder().direction(ReceiptDirection.RECEIPT).amount(new BigDecimal("300.00")).build()));
        when(journalEntryRepository.findBySourceTypeAndSourceIdAndPurpose(any(), eq("I1"), any())).thenReturn(Optional.empty());

        service.issue("CN1");

        assertThat(inv.getStatus()).isEqualTo(InvoiceLifecycle.PARTIALLY_PAID);
        verify(journalService, never()).post(any());
    }

    @Test
    void aZeroCreditNoteOnARecognisedInvoicePostsNothing() {
        CreditNote note = draftNote("0.00");
        stubIssue(note, invoice());
        when(journalEntryRepository.findBySourceTypeAndSourceIdAndPurpose(JournalSourceType.INVOICE, "I1", JournalPurpose.INVOICE_RAISED_DEFERRED))
                .thenReturn(Optional.of(JournalEntry.builder().id("JE1").build()));

        service.issue("CN1");

        verify(journalService, never()).post(any());
        verify(customerLedgerService).post(any());
    }

    @Test
    void aCreditForOneBookingOfACombinedInvoiceRefundsOnlyWhatThatBookingOverpaid() {
        CreditNote note = draftNote("300.00");
        note.setBookingId("B2");
        Invoice inv = invoice();
        stubIssue(note, inv);
        when(invoiceLineItemRepository.findByInvoiceIdOrderBySortOrderAsc("I1")).thenReturn(List.of(
                InvoiceLineItem.builder().bookingId("B1").taxableValue(new BigDecimal("600")).build(),
                InvoiceLineItem.builder().bookingId("B2").taxableValue(new BigDecimal("400")).build()));
        when(bookingAccountingSync.positionOn(inv, "B2", "CN1")).thenReturn(new BookingAccountingSync.Position(new BigDecimal("420.00"), new BigDecimal("420.00")));
        when(journalEntryRepository.findBySourceTypeAndSourceIdAndPurpose(any(), eq("I1"), any())).thenReturn(Optional.empty());

        CreditNoteResponse res = service.issue("CN1");

        assertThat(res.getRefundableAmount()).isEqualByComparingTo("300.00");
    }

    // ------------------------------------------------------------------ commission-agent credit note journals

    private JournalLine jl(SystemAccount account, String credit) {
        return JournalLine.builder().accountCode(account.code()).creditAmountInr(credit == null ? null : new BigDecimal(credit)).build();
    }

    private Optional<JournalPosting> issueCommissionAgent(CreditNote note, Invoice inv, List<JournalLine> original) {
        stubIssue(note, inv);
        when(journalEntryRepository.findBySourceTypeAndSourceIdAndPurpose(JournalSourceType.INVOICE, "I1", JournalPurpose.INVOICE_RAISED_DEFERRED))
                .thenReturn(Optional.empty());
        when(journalEntryRepository.findBySourceTypeAndSourceIdAndPurpose(JournalSourceType.INVOICE, "I1", JournalPurpose.INVOICE_RAISED_RECOGNIZED))
                .thenReturn(Optional.empty());
        when(journalEntryRepository.findBySourceTypeAndSourceIdAndPurpose(JournalSourceType.INVOICE, "I1", JournalPurpose.INVOICE_RAISED_COMMISSION_AGENT))
                .thenReturn(original == null ? Optional.empty() : Optional.of(JournalEntry.builder().id("JE1").build()));
        if (original != null && inv.getGrandTotalInr() != null && inv.getGrandTotalInr().signum() != 0) {
            when(journalLineRepository.findByJournalEntryIdOrderByLineNo("JE1")).thenReturn(original);
        }
        service.issue(note.getId());
        ArgumentCaptor<JournalPosting> j = ArgumentCaptor.forClass(JournalPosting.class);
        verify(journalService, org.mockito.Mockito.atMost(1)).post(j.capture());
        return j.getAllValues().stream().findFirst();
    }

    @Test
    void aCommissionAgentInvoiceWithNoJournalOrNoTotalSkipsTheCreditNoteJournal() {
        assertThat(issueCommissionAgent(draftNote("100.00"), invoice(), null)).isEmpty();
    }

    @Test
    void aCommissionAgentInvoiceWithAZeroTotalSkipsTheCreditNoteJournal() {
        Invoice zero = invoice();
        zero.setGrandTotalInr(BigDecimal.ZERO);
        assertThat(issueCommissionAgent(draftNote("0.00"), zero, List.of())).isEmpty();
    }

    @Test
    void aCommissionAgentInvoiceWithNoTotalFigureSkipsTheCreditNoteJournal() {
        Invoice none = invoice();
        none.setGrandTotalInr(null);
        CreditNote note = draftNote("0.00");
        assertThat(issueCommissionAgent(note, none, List.of())).isEmpty();
    }

    @Test
    void aCommissionAgentCreditWithNothingToReversePostsNothing() {
        assertThat(issueCommissionAgent(draftNote("100.00"), invoice(), List.of(
                jl(SystemAccount.ACCOUNTS_RECEIVABLE, null), jl(SystemAccount.SERVICE_FEE_INCOME, null)))).isEmpty();
    }

    @Test
    void aCancellationSpreadsTheAgencysPartOverItsFeeAndGstLines() {
        CreditNote note = draftNote("300.00");
        note.setSupplierRetained(new BigDecimal("900.00"));
        note.setNoteDate(LocalDate.of(2026, 10, 6));
        Optional<JournalPosting> j = issueCommissionAgent(note, invoice(), List.of(
                jl(SystemAccount.CLIENT_PASS_THROUGH_PAYABLE, "1000.00"),
                jl(SystemAccount.SERVICE_FEE_INCOME, "200.00"),
                jl(SystemAccount.OUTPUT_GST_PAYABLE, "100.00"),
                jl(SystemAccount.TCS_PAYABLE, "0")));

        Map<String, BigDecimal> debits = new LinkedHashMap<>();
        j.orElseThrow().lines().stream().filter(l -> l.debitAmount().signum() > 0).forEach(l -> debits.put(l.accountCode(), l.debitAmount()));
        // 100 back from pass-through (1000 - 900 kept), the other 200 split 2:1 over fee and GST
        assertThat(debits.get(SystemAccount.CLIENT_PASS_THROUGH_PAYABLE.code())).isEqualByComparingTo("100.00");
        assertThat(debits.get(SystemAccount.SERVICE_FEE_INCOME.code())).isEqualByComparingTo("133.33");
        assertThat(debits.get(SystemAccount.OUTPUT_GST_PAYABLE.code())).isEqualByComparingTo("66.67");
        assertThat(j.get().entryDate()).isEqualTo(LocalDate.of(2026, 10, 6));
    }

    @Test
    void aCancellationRefundedWhollyFromThePassThroughLeavesTheAgencyLinesAlone() {
        CreditNote note = draftNote("300.00");
        note.setSupplierRetained(BigDecimal.ZERO);
        Optional<JournalPosting> j = issueCommissionAgent(note, invoice(), List.of(
                jl(SystemAccount.CLIENT_PASS_THROUGH_PAYABLE, "1000.00"), jl(SystemAccount.SERVICE_FEE_INCOME, "200.00")));

        assertThat(j.orElseThrow().lines()).extracting(JournalLinePosting::accountCode).containsExactly(
                SystemAccount.CLIENT_PASS_THROUGH_PAYABLE.code(), SystemAccount.ACCOUNTS_RECEIVABLE.code());
    }

    @Test
    void aSharePaiseThatRoundsToNothingIsNotDebited() {
        CreditNote note = draftNote("300.00");
        note.setSupplierRetained(new BigDecimal("700.01"));
        Optional<JournalPosting> j = issueCommissionAgent(note, invoice(), List.of(
                jl(SystemAccount.CLIENT_PASS_THROUGH_PAYABLE, "1000.00"), jl(SystemAccount.SERVICE_FEE_INCOME, "100.00"),
                jl(SystemAccount.OUTPUT_GST_PAYABLE, "100.00")));

        assertThat(j.orElseThrow().lines()).extracting(JournalLinePosting::accountCode).containsExactly(
                SystemAccount.CLIENT_PASS_THROUGH_PAYABLE.code(), SystemAccount.SERVICE_FEE_INCOME.code(), SystemAccount.ACCOUNTS_RECEIVABLE.code());
        assertThat(j.get().lines().get(2).creditAmount()).isEqualByComparingTo("300.00");
    }

    @Test
    void thePreviewOfACombinedInvoiceWithNoBookingCreditsTheWholeInvoice() {
        Invoice inv = invoice();
        when(invoiceLineItemRepository.findByInvoiceIdOrderBySortOrderAsc("I1")).thenReturn(List.of(
                InvoiceLineItem.builder().bookingId("B1").taxableValue(new BigDecimal("600")).build(),
                InvoiceLineItem.builder().bookingId("B2").taxableValue(new BigDecimal("400")).build()));
        when(paymentReceiptRepository.findByInvoiceIdOrderByReceivedOnAscCreatedAtAsc("I1")).thenReturn(List.of(
                PaymentReceipt.builder().direction(ReceiptDirection.RECEIPT).amount(new BigDecimal("1050.00")).build()));

        assertThat(service.previewRefundable(inv, null, BigDecimal.ZERO, null)).isEqualByComparingTo("1050.00");
    }

    @Test
    void aSupplierShareOnAPrincipalInvoiceOrAZeroRateIsIgnoredOrReadAsPar() {
        Invoice principal = invoice();
        principal.setBillingModel(InvoiceBillingModel.PRINCIPAL);
        Invoice zeroRate = invoice();
        zeroRate.setId("I2");
        zeroRate.setBillingModel(InvoiceBillingModel.COMMISSION_AGENT);
        zeroRate.setFxRateToInr(BigDecimal.ZERO);
        when(journalEntryRepository.findBySourceTypeAndSourceIdAndPurpose(JournalSourceType.INVOICE, "I2", JournalPurpose.INVOICE_RAISED_COMMISSION_AGENT))
                .thenReturn(Optional.of(JournalEntry.builder().id("JE2").build()));
        when(journalLineRepository.findByJournalEntryIdOrderByLineNo("JE2")).thenReturn(List.of(
                jl(SystemAccount.CLIENT_PASS_THROUGH_PAYABLE, "1000.00")));
        when(paymentReceiptRepository.findByInvoiceIdOrderByReceivedOnAscCreatedAtAsc(any())).thenReturn(List.of(
                PaymentReceipt.builder().direction(ReceiptDirection.RECEIPT).amount(new BigDecimal("1050.00")).build()));

        assertThat(service.previewRefundable(principal, null, BigDecimal.ZERO, new BigDecimal("100"))).isEqualByComparingTo("1050.00");
        assertThat(service.previewRefundable(zeroRate, null, BigDecimal.ZERO, new BigDecimal("100"))).isEqualByComparingTo("1000.00");
        verify(journalEntryRepository, never()).findBySourceTypeAndSourceIdAndPurpose(any(), eq("I1"), any());
    }

    @Test
    void aCancellationKeptWhollyByTheSupplierWithNoAgencyLinesPostsNothing() {
        CreditNote note = draftNote("300.00");
        note.setSupplierRetained(new BigDecimal("2000.00"));
        note.setNoteDate(LocalDate.of(2026, 10, 4));
        Optional<JournalPosting> j = issueCommissionAgent(note, invoice(), List.of(jl(SystemAccount.CLIENT_PASS_THROUGH_PAYABLE, "1000.00")));

        assertThat(j).isEmpty();
    }

    // ------------------------------------------------------------------ cancel / refund

    @Test
    void cancellingADraftPostsNothingAndACancelledNoteIsFinal() {
        CreditNote draft = draftNote("100.00");
        CreditNote cancelled = draftNote("100.00");
        cancelled.setId("CN2");
        cancelled.setStatus(CreditNoteStatus.CANCELLED);
        when(creditNoteRepository.findById("CN1")).thenReturn(Optional.of(draft));
        when(creditNoteRepository.findById("CN2")).thenReturn(Optional.of(cancelled));

        assertThat(service.cancel("CN1", "typo").getStatus()).isEqualTo(CreditNoteStatus.CANCELLED);
        assertThatThrownBy(() -> service.cancel("CN2", "x")).hasMessage("This credit note is already cancelled");
        verify(customerLedgerService, never()).post(any());
        verify(invoiceRepository, never()).findById(any());
    }

    @Test
    void cancellingAnIssuedNoteReversesItsJournalAndNeedsItsInvoice() {
        CreditNote issued = draftNote("100.00");
        issued.setStatus(CreditNoteStatus.ISSUED);
        issued.setCreditNoteNumber("CN-0001");
        when(creditNoteRepository.findById("CN1")).thenReturn(Optional.of(issued));
        Invoice inv = invoice();
        inv.setCreditNoteTotal(new BigDecimal("100.00"));
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(inv), Optional.empty());
        when(journalEntryRepository.findBySourceTypeAndSourceIdAndPurpose(JournalSourceType.CREDIT_NOTE, "CN1", JournalPurpose.CREDIT_NOTE_ISSUED))
                .thenReturn(Optional.of(JournalEntry.builder().id("JE9").build()));

        service.cancel("CN1", "raised in error");

        verify(journalService).reverse("JE9", "Credit note CN-0001 cancelled: raised in error");
        assertThat(inv.getCreditNoteTotal()).isEqualByComparingTo("0");
        CreditNote again = draftNote("100.00");
        again.setStatus(CreditNoteStatus.ISSUED);
        when(creditNoteRepository.findById("CN1")).thenReturn(Optional.of(again));
        assertThatThrownBy(() -> service.cancel("CN1", "x")).hasMessage("Invoice not found: I1");
    }

    private CreditNoteRefundRequest refund(String amount, PaymentMode mode) {
        CreditNoteRefundRequest r = new CreditNoteRefundRequest();
        r.setAmount(new BigDecimal(amount));
        r.setPaymentMode(mode);
        r.setReceivedOn(LocalDate.of(2026, 10, 5));
        return r;
    }

    @Test
    void onlyAnIssuedNoteIsRefunded() {
        when(creditNoteRepository.findById("CN1")).thenReturn(Optional.of(draftNote("100")));

        assertThatThrownBy(() -> service.refund("CN1", refund("10", PaymentMode.CASH))).hasMessage("Only an issued credit note can be refunded");
    }

    @Test
    void aCashRefundPaysOutOfCashInHandEvenWhenTheInvoiceIsGone() {
        CreditNote issued = draftNote("100.00");
        issued.setStatus(CreditNoteStatus.ISSUED);
        issued.setRefundableAmount(new BigDecimal("100.00"));
        when(creditNoteRepository.findById("CN1")).thenReturn(Optional.of(issued));
        when(documentNumberService.next(DocumentKind.RECEIPT, LocalDate.of(2026, 10, 5))).thenReturn("RCP-1");
        when(invoiceRepository.findById("I1")).thenReturn(Optional.empty());

        service.refund("CN1", refund("60.00", PaymentMode.CASH));

        ArgumentCaptor<JournalPosting> j = ArgumentCaptor.forClass(JournalPosting.class);
        verify(journalService).post(j.capture());
        assertThat(j.getValue().lines().get(1).accountCode()).isEqualTo(SystemAccount.CASH_IN_HAND.code());
        assertThat(issued.getRefundedAmount()).isEqualByComparingTo("60.00");
        verify(bookingAccountingSync).syncPayment("B1");
    }

    @Test
    void aZeroRefundPostsNoJournal() {
        CreditNote issued = draftNote("100.00");
        issued.setStatus(CreditNoteStatus.ISSUED);
        when(creditNoteRepository.findById("CN1")).thenReturn(Optional.of(issued));
        when(documentNumberService.next(DocumentKind.RECEIPT, LocalDate.of(2026, 10, 5))).thenReturn("RCP-1");

        service.refund("CN1", refund("0", PaymentMode.CASH));

        verify(journalService, never()).post(any());
        ArgumentCaptor<LedgerPosting> l = ArgumentCaptor.forClass(LedgerPosting.class);
        verify(customerLedgerService).post(l.capture());
        assertThat(l.getValue().narration()).isEqualTo("Refund paid to the client by the agency - RCP-1 against credit note null");
    }

    // ------------------------------------------------------------------ read

    @Test
    void agentsCannotReadCreditNotes() {
        signInAs(UserType.AGENT);
        when(creditNoteRepository.findById("CN1")).thenReturn(Optional.of(draftNote("1")));

        assertThatThrownBy(() -> service.get("CN1")).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void anAccountantReadsAndPrintsANote() {
        CreditNote n = draftNote("100.00");
        n.setStatus(CreditNoteStatus.ISSUED);
        n.setCreditNoteNumber("CN-0001");
        n.setInvoiceNumber("INV-1");
        n.setClientName("Asha");
        n.setNoteDate(LocalDate.of(2026, 10, 1));
        n.setReason(CreditNoteReason.GOODWILL);
        when(creditNoteRepository.findById("CN1")).thenReturn(Optional.of(n));

        assertThat(service.get("CN1").getCreditNoteNumber()).isEqualTo("CN-0001");
        assertThat(new String(service.getPdf("CN1"), 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");
    }

    @Test
    @SuppressWarnings("unchecked")
    void listFiltersByInvoiceAndClientPagedOrNot() {
        Pageable page = PageRequest.of(0, 5);
        when(creditNoteRepository.findAll(any(Specification.class), eq(page))).thenReturn(new PageImpl<>(List.of(draftNote("1")), page, 1));
        when(creditNoteRepository.findAll(any(Specification.class), eq(Sort.by(Sort.Direction.DESC, "createdAt")))).thenReturn(List.of());

        PagedResponse<CreditNoteResponse> paged = (PagedResponse<CreditNoteResponse>) service.list("I1", "K1", page);
        List<CreditNoteResponse> all = (List<CreditNoteResponse>) service.list(null, null, null);

        assertThat(paged.getContent()).extracting(CreditNoteResponse::getId).containsExactly("CN1");
        assertThat(all).isEmpty();
        ArgumentCaptor<Specification<CreditNote>> spec = ArgumentCaptor.forClass(Specification.class);
        verify(creditNoteRepository).findAll(spec.capture(), eq(page));
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        spec.getValue().toPredicate(mock(Root.class, RETURNS_DEEP_STUBS), mock(CriteriaQuery.class), cb);
        verify(cb).equal(any(), eq("I1"));
        verify(cb).equal(any(), eq("K1"));
    }

    @Test
    void thePreviewCapsTheFeeBetweenZeroAndTheBookingsTaxable() {
        Invoice inv = invoice();
        when(paymentReceiptRepository.findByInvoiceIdOrderByReceivedOnAscCreatedAtAsc("I1")).thenReturn(List.of(
                PaymentReceipt.builder().direction(ReceiptDirection.RECEIPT).amount(new BigDecimal("1050.00")).build()));

        assertThat(service.previewRefundable(inv, null, new BigDecimal("5000"), null)).isEqualByComparingTo("0");
        assertThat(service.previewRefundable(inv, null, new BigDecimal("-10"), null)).isEqualByComparingTo("1050.00");
    }
}
