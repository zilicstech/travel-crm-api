package com.voyra.crm.service;

import com.voyra.crm.dto.BookingLifecycleResponse;
import com.voyra.crm.dto.BookingPaymentRequest;
import com.voyra.crm.dto.PaymentReceiptRequest;
import com.voyra.crm.entity.Booking;
import com.voyra.crm.entity.CreditNote;
import com.voyra.crm.entity.Invoice;
import com.voyra.crm.entity.PaymentReceipt;
import com.voyra.crm.entity.SupplierInvoice;
import com.voyra.crm.entity.SupplierPayment;
import com.voyra.crm.enums.BookingStatus;
import com.voyra.crm.enums.CreditNoteStatus;
import com.voyra.crm.enums.InvoiceDocumentType;
import com.voyra.crm.enums.InvoiceLifecycle;
import com.voyra.crm.enums.PaymentMode;
import com.voyra.crm.enums.ReceiptDirection;
import com.voyra.crm.enums.SupplierInvoiceStatus;
import com.voyra.crm.enums.SupplierPaymentDirection;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.repository.BookingDocumentRepository;
import com.voyra.crm.repository.BookingRepository;
import com.voyra.crm.repository.CreditNoteRepository;
import com.voyra.crm.repository.InvoiceRepository;
import com.voyra.crm.repository.PaymentReceiptRepository;
import com.voyra.crm.repository.SupplierInvoiceRepository;
import com.voyra.crm.repository.SupplierPaymentRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import com.voyra.crm.service.BookingLifecycleService.Facts;
import com.voyra.crm.service.BookingLifecycleService.Viewer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

/** Branch coverage for BookingLifecycleService under strict stubs. */
@ExtendWith(MockitoExtension.class)
class BookingLifecycleServiceCoverageTest {

    @Mock BookingService bookingService;
    @Mock BookingRepository bookingRepository;
    @Mock BookingDocumentRepository bookingDocumentRepository;
    @Mock InvoiceRepository invoiceRepository;
    @Mock PaymentReceiptRepository paymentReceiptRepository;
    @Mock SupplierInvoiceRepository supplierInvoiceRepository;
    @Mock SupplierPaymentRepository supplierPaymentRepository;
    @Mock InvoiceDocumentService invoiceDocumentService;
    @Mock PaymentReceiptService paymentReceiptService;
    @Mock CreditNoteRepository creditNoteRepository;
    @Mock BookingAccountingSync bookingAccountingSync;
    @Spy BaseCurrency baseCurrency = new BaseCurrency(null);
    @InjectMocks BookingLifecycleService service;

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private void signInAs(UserType type) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal("U1", "user", type, "T1"), null, List.of()));
    }

    private static Booking booking(BookingStatus status) {
        return Booking.builder().id("B1").bookingNumber(7L).clientId("C1").bookingStatus(status)
                .sellingPrice(d("10000")).netCost(d("8000"))
                .invoicedTotalInr(BigDecimal.ZERO).receivedTotalInr(BigDecimal.ZERO).refundedTotalInr(BigDecimal.ZERO)
                .customerRefundDue(BigDecimal.ZERO).supplierRefundExpected(BigDecimal.ZERO).supplierRefundReceived(BigDecimal.ZERO)
                .build();
    }

    private static Invoice invoice(String id, InvoiceLifecycle status, String balance) {
        return Invoice.builder().id(id).documentType(InvoiceDocumentType.TAX_INVOICE).status(status).invoiceNumber("INV/" + id)
                .grandTotalInr(d("10000")).balanceDue(d(balance)).balanceDueInr(d(balance)).fxRateToInr(BigDecimal.ONE).build();
    }

    private static BookingPaymentRequest payment(String amount, String reference) {
        BookingPaymentRequest r = new BookingPaymentRequest();
        r.setAmount(d(amount));
        r.setPaymentMode(PaymentMode.UPI);
        r.setReceivedOn(LocalDate.of(2026, 10, 17));
        r.setReference(reference);
        return r;
    }

    private static Map<String, BookingLifecycleResponse.Step> steps(BookingLifecycleResponse r) {
        return r.getSteps().stream().collect(Collectors.toMap(BookingLifecycleResponse.Step::getKey, s -> s));
    }

    private void stubPayment(Booking b, List<Invoice> invoices) {
        when(bookingService.findAccessibleBooking("B1")).thenReturn(b);
        when(invoiceRepository.findByBookingIdAndDocumentTypeAndStatusNotOrderByCreatedAtAsc("B1", InvoiceDocumentType.TAX_INVOICE, InvoiceLifecycle.CANCELLED))
                .thenReturn(invoices);
    }

    private List<PaymentReceiptRequest> recorded(int n) {
        ArgumentCaptor<PaymentReceiptRequest> c = ArgumentCaptor.forClass(PaymentReceiptRequest.class);
        verify(paymentReceiptService, times(n)).record(c.capture());
        return c.getAllValues();
    }

    // ------------------------------------------------------------------ recordPayment

    @Test
    void aCancelledBookingTakesOnlyItsChargesAndNotMore() {
        signInAs(UserType.ACCOUNTANT);
        Booking b = booking(BookingStatus.CANCELLED);
        Invoice inv = invoice("I1", InvoiceLifecycle.ISSUED, "500");
        stubPayment(b, List.of(inv));
        when(invoiceDocumentService.weightOf(inv, "B1")).thenReturn(BigDecimal.ONE);

        assertThatThrownBy(() -> service.recordPayment("B1", payment("600", null)))
                .hasMessage("More than the 500 still owed on this cancelled booking");

        service.recordPayment("B1", payment("500", "  UTR9 "));

        PaymentReceiptRequest r = recorded(1).get(0);
        assertThat(r.getInvoiceId()).isEqualTo("I1");
        assertThat(r.getAmount()).isEqualByComparingTo("500");
        assertThat(r.getInstrumentRef()).isEqualTo("UTR9");
    }

    @Test
    void aSharedInvoiceIsPaidOnlyWhatThisBookingOwesAndDraftsAndSettledInvoicesAreSkipped() {
        signInAs(UserType.AGENCY_OWNER);
        Booking b = booking(BookingStatus.CONFIRMED);
        Invoice draft = invoice("I0", InvoiceLifecycle.DRAFT, "100");
        Invoice settled = invoice("I1", InvoiceLifecycle.PAID, "0");
        Invoice shared = invoice("I2", InvoiceLifecycle.ISSUED, "9000");
        Invoice later = invoice("I3", InvoiceLifecycle.ISSUED, "100");
        stubPayment(b, List.of(draft, settled, shared, later));
        when(invoiceDocumentService.weightOf(settled, "B1")).thenReturn(BigDecimal.ONE);
        when(invoiceDocumentService.weightOf(shared, "B1")).thenReturn(d("0.4"));
        when(invoiceDocumentService.weightOf(later, "B1")).thenReturn(BigDecimal.ONE);
        when(bookingAccountingSync.positionOn(shared, "B1", null)).thenReturn(new BookingAccountingSync.Position(d("4000"), d("1000")));

        service.recordPayment("B1", payment("3000", " "));

        List<PaymentReceiptRequest> r = recorded(1);
        assertThat(r.get(0).getInvoiceId()).isEqualTo("I2");
        assertThat(r.get(0).getAmount()).isEqualByComparingTo("3000");
        assertThat(r.get(0).getInstrumentRef()).isNull();
    }

    @Test
    void aForeignInvoiceIsPaidAtItsRateAndTheWholeBalanceExactly() {
        signInAs(UserType.AGENCY_OWNER);
        Booking b = booking(BookingStatus.CONFIRMED);
        Invoice usd = invoice("I1", InvoiceLifecycle.ISSUED, "100.00");
        usd.setFxRateToInr(d("83.33"));
        Invoice usd2 = invoice("I2", InvoiceLifecycle.ISSUED, "50.00");
        usd2.setFxRateToInr(d("83.33"));
        stubPayment(b, List.of(usd, usd2));
        when(invoiceDocumentService.weightOf(usd, "B1")).thenReturn(BigDecimal.ONE);
        when(invoiceDocumentService.weightOf(usd2, "B1")).thenReturn(BigDecimal.ONE);

        // 100 USD owes 8333.00 base; 10000 base settles it and pays 1667 / 83.33 = 20.00 of the second
        service.recordPayment("B1", payment("10000", null));

        List<PaymentReceiptRequest> r = recorded(2);
        assertThat(r.get(0).getAmount()).isEqualByComparingTo("100.00");
        assertThat(r.get(1).getAmount()).isEqualByComparingTo("20.00");
    }

    @Test
    void aPaymentUsedUpOnTheFirstInvoiceStopsThere() {
        signInAs(UserType.AGENCY_OWNER);
        Booking b = booking(BookingStatus.CONFIRMED);
        Invoice first = invoice("I1", InvoiceLifecycle.ISSUED, "1000");
        Invoice second = invoice("I2", InvoiceLifecycle.ISSUED, "1000");
        first.setFxRateToInr(null);
        stubPayment(b, List.of(first, second));
        when(invoiceDocumentService.weightOf(first, "B1")).thenReturn(BigDecimal.ONE);
        when(invoiceDocumentService.weightOf(second, "B1")).thenReturn(BigDecimal.ONE);

        service.recordPayment("B1", payment("1000", null));

        assertThat(recorded(1).get(0).getInvoiceId()).isEqualTo("I1");
    }

    // ------------------------------------------------------------------ getLifecycle / gather

    @Test
    void anAgentReadsTheLifecycleWithSharedInvoiceFiguresAndIssuedCreditNotes() {
        signInAs(UserType.AGENT);
        Booking b = booking(BookingStatus.CONFIRMED);
        when(bookingService.findAccessibleBooking("B1")).thenReturn(b);
        Invoice shared = invoice("I1", InvoiceLifecycle.ISSUED, "5000");
        Invoice own = invoice("I2", InvoiceLifecycle.ISSUED, "0");
        when(invoiceRepository.findByBookingId("B1")).thenReturn(List.of(shared, own));
        when(invoiceDocumentService.weightOf(shared, "B1")).thenReturn(d("0.5"));
        when(invoiceDocumentService.weightOf(own, "B1")).thenReturn(BigDecimal.ONE);
        when(bookingAccountingSync.positionOn(shared, "B1", null)).thenReturn(new BookingAccountingSync.Position(d("3000"), d("1000")));
        when(creditNoteRepository.findByBookingIdAndStatusNot("B1", CreditNoteStatus.CANCELLED)).thenReturn(List.of(
                CreditNote.builder().id("CN1").status(CreditNoteStatus.ISSUED).invoiceId("I1").build(),
                CreditNote.builder().id("CN2").status(CreditNoteStatus.DRAFT).build()));
        when(bookingService.invoicedPrice("B1")).thenReturn(d("10000"));

        BookingLifecycleResponse r = service.getLifecycle("B1");

        assertThat(r.getToCollectInr()).isEqualByComparingTo("2000");
        assertThat(r.getClientWalletInr()).isNull();
        assertThat(r.getRecords()).extracting(BookingLifecycleResponse.MoneyRecord::getKind).contains("CREDIT_NOTE");
        assertThat(r.getRecords()).filteredOn(m -> m.getKind().equals("CREDIT_NOTE")).hasSize(1);
    }

    @Test
    void anAccountantReadsTheLifecycleWithTheWallet() {
        signInAs(UserType.ACCOUNTANT);
        Booking b = booking(BookingStatus.CONFIRMED);
        when(bookingService.findAccessibleBooking("B1")).thenReturn(b);
        when(paymentReceiptService.availableWalletFor("C1", "B1")).thenReturn(d("300"));

        BookingLifecycleResponse r = service.getLifecycle("B1");

        assertThat(r.getClientWalletInr()).isEqualByComparingTo("300");
        assertThat(r.getAllowedActions()).doesNotContain("UPLOAD_VOUCHER", "CANCEL");
        assertThat(r.getToCollectInr()).isEqualByComparingTo("9700");
    }

    // ------------------------------------------------------------------ build

    @Test
    void aCompletedBookingWithADraftAndAProformaIsNotInvoicedYet() {
        Booking b = booking(BookingStatus.COMPLETED);
        b.setSupplier("  Agoda  ");
        Invoice draft = invoice("I1", InvoiceLifecycle.DRAFT, "0");
        Invoice proforma = invoice("I2", InvoiceLifecycle.PROFORMA_ISSUED, "0");
        proforma.setDocumentType(InvoiceDocumentType.PROFORMA);
        Invoice cancelled = invoice("I3", InvoiceLifecycle.CANCELLED, "0");
        SupplierInvoice noName = SupplierInvoice.builder().id("S1").vendorName(null).status(SupplierInvoiceStatus.APPROVED).build();
        SupplierInvoice blank = SupplierInvoice.builder().id("S2").vendorName(" ").status(SupplierInvoiceStatus.APPROVED).build();

        BookingLifecycleResponse r = BookingLifecycleService.build(new Facts(b, 0, List.of(draft, proforma, cancelled), List.of(),
                List.of(noName, blank), List.of(), null, BigDecimal.ZERO), Viewer.OWNER, "USD");

        assertThat(steps(r).get("CREATED").getSummary()).isEqualTo("Completed with Agoda");
        assertThat(steps(r).get("INVOICE").getSummary()).isEqualTo("Draft invoice not issued yet");
        assertThat(steps(r).get("INVOICE").getState()).isEqualTo("OPEN");
        assertThat(r.getAllowedActions()).contains("GENERATE_INVOICE", "RECORD_PAYMENT", "UPLOAD_VOUCHER").doesNotContain("CANCEL");
        assertThat(r.getRecords()).extracting(BookingLifecycleResponse.MoneyRecord::getKind).containsExactly("INVOICE", "SUPPLIER_BILL", "SUPPLIER_BILL");
        assertThat(steps(r).get("PAYMENT").getSummary()).isEqualTo("USD 10,000 to collect");
    }

    @Test
    void anUnpricedBookingOffersNoInvoiceAndAPendingOneNeverDoes() {
        Booking unpriced = booking(BookingStatus.CONFIRMED);
        unpriced.setSellingPrice(BigDecimal.ZERO);
        Booking pending = booking(BookingStatus.PENDING);

        assertThat(BookingLifecycleService.build(new Facts(unpriced, 1, List.of(), List.of(), List.of(), List.of(), BigDecimal.ZERO,
                BigDecimal.ZERO), Viewer.OWNER).getAllowedActions()).doesNotContain("GENERATE_INVOICE");
        assertThat(BookingLifecycleService.build(new Facts(pending, 1, List.of(), List.of(), List.of(), List.of(), BigDecimal.ZERO,
                BigDecimal.ZERO), Viewer.OWNER).getAllowedActions()).doesNotContain("GENERATE_INVOICE");
    }

    @Test
    void aCancelledBookingShowsPartialRefundsTheCheckAndTheAdvanceItHeld() {
        Booking b = booking(BookingStatus.CANCELLED);
        b.setSupplier("Agoda");
        b.setSupplierRefundExpected(d("1000"));
        b.setSupplierRefundReceived(d("400"));
        b.setCustomerRefundDue(d("900"));
        b.setRefundedTotalInr(d("300"));
        b.setRefundCheckedAt(LocalDateTime.now());

        BookingLifecycleResponse r = BookingLifecycleService.build(new Facts(b, 0, List.of(), List.of(), List.of(), List.of(),
                d("250"), BigDecimal.ZERO), Viewer.OWNER);

        assertThat(steps(r).get("CREATED").getSummary()).isEqualTo("Booked with Agoda");
        assertThat(steps(r).get("SUPPLIER_REFUND").getState()).isEqualTo("PARTIAL");
        assertThat(steps(r).get("SUPPLIER_REFUND").getSummary()).endsWith(" - cross-checked");
        assertThat(steps(r).get("CLIENT_REFUND").getState()).isEqualTo("PARTIAL");
        assertThat(steps(r).get("PAYMENT").getSummary()).isEqualTo("₹250 held as an advance before cancelling");
        assertThat(r.getAllowedActions()).containsExactlyInAnyOrder("REFUND_CLIENT", "SUPPLIER_REFUND");
        assertThat(r.getSupplierRefundOutstandingInr()).isEqualByComparingTo("600");
    }

    @Test
    void aSettledCancelledBookingAlreadyCheckedOffersNothingMore() {
        Booking b = booking(BookingStatus.CANCELLED);
        b.setRefundCheckedAt(LocalDateTime.now());

        BookingLifecycleResponse withNoWallet = BookingLifecycleService.build(new Facts(b, 0, List.of(), List.of(), List.of(), List.of(),
                null, BigDecimal.ZERO), Viewer.ACCOUNTANT);

        assertThat(withNoWallet.getAllowedActions()).isEmpty();
        assertThat(steps(withNoWallet).get("PAYMENT").getState()).isEqualTo("NA");
    }

    @Test
    void theCrossCheckWaitsForEveryRefundAndTheClaim() {
        Booking clientOpen = booking(BookingStatus.CANCELLED);
        clientOpen.setCustomerRefundDue(d("100"));
        Booking supplierOpen = booking(BookingStatus.CANCELLED);
        supplierOpen.setSupplierRefundExpected(d("100"));
        Booking claimed = booking(BookingStatus.CANCELLED);
        claimed.setSupplierRefundClaimAmount(d("100"));

        for (Booking b : List.of(clientOpen, supplierOpen, claimed)) {
            assertThat(BookingLifecycleService.build(new Facts(b, 0, List.of(), List.of(), List.of(), List.of(), BigDecimal.ZERO,
                    BigDecimal.ZERO), Viewer.OWNER).getAllowedActions()).doesNotContain("CROSS_CHECK");
        }
        assertThat(BookingLifecycleService.build(new Facts(supplierOpen, 0, List.of(), List.of(), List.of(), List.of(), BigDecimal.ZERO,
                BigDecimal.ZERO), Viewer.OWNER).getSteps()).filteredOn(s -> s.getKey().equals("SUPPLIER_REFUND"))
                .extracting(BookingLifecycleResponse.Step::getState).containsExactly("OPEN");
    }

    @Test
    void everyKindOfMoneyRecordIsNamedAndSortedByDate() {
        Booking b = booking(BookingStatus.CONFIRMED);
        Invoice withFile = invoice("I1", InvoiceLifecycle.ISSUED, "0");
        withFile.setPdfFileKey("k");
        withFile.setInvoiceDate(LocalDate.of(2026, 10, 1));
        PaymentReceipt refund = PaymentReceipt.builder().id("R1").direction(ReceiptDirection.REFUND).receivedOn(LocalDate.of(2026, 10, 5))
                .paymentMode(PaymentMode.UPI).reversesReceiptId("R0").build();
        PaymentReceipt advance = PaymentReceipt.builder().id("R2").direction(ReceiptDirection.RECEIPT).isAdvance(true).build();
        PaymentReceipt onAccountNotAdvance = PaymentReceipt.builder().id("R3").direction(ReceiptDirection.RECEIPT).isAdvance(false)
                .receivedOn(LocalDate.of(2026, 10, 2)).reversedAt(LocalDateTime.now()).build();
        PaymentReceipt applied = PaymentReceipt.builder().id("R4").direction(ReceiptDirection.RECEIPT).invoiceId("I1").appliedFromAdvance(true)
                .receivedOn(LocalDate.of(2026, 10, 3)).build();
        PaymentReceipt nullAdvance = PaymentReceipt.builder().id("R5").direction(ReceiptDirection.RECEIPT).isAdvance(null)
                .receivedOn(LocalDate.of(2026, 10, 4)).build();
        SupplierPayment paid = SupplierPayment.builder().id("P1").direction(SupplierPaymentDirection.PAYMENT).paymentMode(PaymentMode.CASH)
                .paidOn(LocalDate.of(2026, 10, 6)).reversedAt(LocalDateTime.now()).build();
        SupplierPayment back = SupplierPayment.builder().id("P2").direction(SupplierPaymentDirection.REFUND_RECEIVED)
                .paidOn(LocalDate.of(2026, 10, 7)).reversesPaymentId("P0").build();
        SupplierPayment plain = SupplierPayment.builder().id("P3").direction(SupplierPaymentDirection.PAYMENT)
                .paidOn(LocalDate.of(2026, 10, 8)).build();

        BookingLifecycleResponse r = BookingLifecycleService.build(new Facts(b, 1, List.of(withFile),
                List.of(refund, advance, onAccountNotAdvance, applied, nullAdvance), List.of(), List.of(paid, back, plain),
                BigDecimal.ZERO, d("10000")), Viewer.OWNER);

        assertThat(r.getRecords()).extracting(BookingLifecycleResponse.MoneyRecord::getId)
                .containsExactly("I1", "R3", "R4", "R5", "R1", "P1", "P2", "P3", "R2");
        Map<String, BookingLifecycleResponse.MoneyRecord> byId = r.getRecords().stream()
                .collect(Collectors.toMap(BookingLifecycleResponse.MoneyRecord::getId, m -> m));
        assertThat(byId.get("I1").isHasFile()).isTrue();
        assertThat(byId.get("R1").getKind()).isEqualTo("REFUND");
        assertThat(byId.get("R1").isReversed()).isTrue();
        assertThat(byId.get("R1").getMode()).isEqualTo("UPI");
        assertThat(byId.get("R2").getKind()).isEqualTo("ADVANCE");
        assertThat(byId.get("R2").getMode()).isNull();
        assertThat(byId.get("R3").getKind()).isEqualTo("RECEIPT");
        assertThat(byId.get("R3").isReversed()).isTrue();
        assertThat(byId.get("R4").getKind()).isEqualTo("ADVANCE_APPLIED");
        assertThat(byId.get("R5").getKind()).isEqualTo("RECEIPT");
        assertThat(byId.get("P1").getKind()).isEqualTo("SUPPLIER_PAYMENT");
        assertThat(byId.get("P1").getMode()).isEqualTo("CASH");
        assertThat(byId.get("P1").isReversed()).isTrue();
        assertThat(byId.get("P2").getKind()).isEqualTo("SUPPLIER_REFUND");
        assertThat(byId.get("P2").isReversed()).isTrue();
        assertThat(byId.get("P3").isReversed()).isFalse();
        assertThat(byId.get("P3").getMode()).isNull();
        verify(paymentReceiptService, never()).record(any());
    }
}
