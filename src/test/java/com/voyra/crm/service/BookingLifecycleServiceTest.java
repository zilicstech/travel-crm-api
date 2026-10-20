package com.voyra.crm.service;

import com.voyra.crm.dto.BookingLifecycleResponse;
import com.voyra.crm.dto.BookingPaymentRequest;
import com.voyra.crm.dto.InvoiceDraftRequest;
import com.voyra.crm.dto.InvoiceResponse;
import com.voyra.crm.dto.PaymentReceiptRequest;
import com.voyra.crm.dto.SupplierPaymentRequest;
import com.voyra.crm.entity.Booking;
import com.voyra.crm.entity.Invoice;
import com.voyra.crm.entity.PaymentReceipt;
import com.voyra.crm.entity.SupplierInvoice;
import com.voyra.crm.entity.SupplierPayment;
import com.voyra.crm.enums.BookingStatus;
import com.voyra.crm.enums.InvoiceDocumentType;
import com.voyra.crm.enums.InvoiceLifecycle;
import com.voyra.crm.enums.PaymentMode;
import com.voyra.crm.enums.ReceiptDirection;
import com.voyra.crm.enums.SupplierInvoiceStatus;
import com.voyra.crm.repository.BookingDocumentRepository;
import com.voyra.crm.repository.BookingRepository;
import com.voyra.crm.repository.InvoiceRepository;
import com.voyra.crm.repository.PaymentReceiptRepository;
import com.voyra.crm.repository.SupplierInvoiceRepository;
import com.voyra.crm.repository.SupplierPaymentRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.service.BookingLifecycleService.Facts;
import com.voyra.crm.service.BookingLifecycleService.Viewer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BookingLifecycleServiceTest {

    @Mock private BookingService bookingService;
    @Mock private BookingRepository bookingRepository;
    @Mock private BookingDocumentRepository bookingDocumentRepository;
    @Mock private InvoiceRepository invoiceRepository;
    @Mock private PaymentReceiptRepository paymentReceiptRepository;
    @Mock private SupplierInvoiceRepository supplierInvoiceRepository;
    @Mock private SupplierPaymentRepository supplierPaymentRepository;
    @Mock private InvoiceDocumentService invoiceDocumentService;
    @Mock private PaymentReceiptService paymentReceiptService;
    @Mock private BookingInvoiceIssuer bookingInvoiceIssuer;
    @Mock private com.voyra.crm.repository.CreditNoteRepository creditNoteRepository;
    @Mock private BookingAccountingSync bookingAccountingSync;
    @Mock private com.voyra.crm.repository.BookingPassengerRepository bookingPassengerRepository;

    @org.mockito.Spy

    private com.voyra.crm.service.BaseCurrency baseCurrency = new com.voyra.crm.service.BaseCurrency(null);


    @InjectMocks private BookingLifecycleService service;

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    @BeforeEach
    void owner() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal("O1", "owner", UserType.AGENCY_OWNER, "T1"), null, List.of()));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static Booking booking(BookingStatus status) {
        return Booking.builder().id("B1").bookingNumber(7L).clientId("C1").bookingStatus(status)
                .sellingPrice(d("10000")).netCost(d("8000"))
                .invoicedTotalInr(BigDecimal.ZERO).receivedTotalInr(BigDecimal.ZERO).refundedTotalInr(BigDecimal.ZERO)
                .customerRefundDue(BigDecimal.ZERO).supplierRefundExpected(BigDecimal.ZERO).supplierRefundReceived(BigDecimal.ZERO)
                .build();
    }

    private static Invoice issued(String total, String balance) {
        return Invoice.builder().id("I1").documentType(InvoiceDocumentType.TAX_INVOICE).status(InvoiceLifecycle.ISSUED)
                .invoiceNumber("INV/1").grandTotalInr(d(total)).balanceDueInr(d(balance)).balanceDue(d(balance)).build();
    }

    private static SupplierInvoice bill(SupplierInvoiceStatus status, String total, String balance) {
        return SupplierInvoice.builder().id("S1").bookingId("B1").vendorId("V1").vendorName("IndiGo").status(status)
                .grandTotalInr(d(total)).balanceDueInr(d(balance)).balanceDue(d(balance)).build();
    }

    private static Facts facts(Booking b, int docs, List<Invoice> invoices, List<SupplierInvoice> bills, String wallet) {
        return new Facts(b, docs, invoices, List.of(), bills, List.of(), d(wallet), BigDecimal.ZERO);
    }

    private static Map<String, String> states(BookingLifecycleResponse r) {
        return r.getSteps().stream().collect(Collectors.toMap(BookingLifecycleResponse.Step::getKey, BookingLifecycleResponse.Step::getState));
    }

    // ---- step states -------------------------------------------------------------------

    @Test
    void freshPendingBookingHasEverythingAheadOfIt() {
        BookingLifecycleResponse r = BookingLifecycleService.build(facts(booking(BookingStatus.PENDING), 0, List.of(), List.of(), "0"), Viewer.OWNER);

        assertThat(states(r)).containsEntry("CREATED", "DONE").containsEntry("VOUCHER", "OPEN").containsEntry("INVOICE", "OPEN")
                .containsEntry("PAYMENT", "OPEN").doesNotContainKey("CANCELLED");
        // Paying the supplier is Accounts' job on the supplier bill, not a booking step.
        assertThat(r.getSteps()).extracting(BookingLifecycleResponse.Step::getKey).containsExactly("CREATED", "VOUCHER", "INVOICE", "PAYMENT");
        assertThat(r.getSteps().get(0).getSummary()).startsWith("Pending");
        assertThat(r.getAllowedActions()).containsExactlyInAnyOrder("UPLOAD_VOUCHER", "CANCEL", "RECORD_PAYMENT");
        assertThat(r.getToCollectInr()).isEqualByComparingTo("10000");
    }

    @Test
    void confirmedWithTheVouchersSupplierNotTheBookingsDefault() {
        Booking b = booking(BookingStatus.CONFIRMED);
        b.setSupplier("Booking.com");

        BookingLifecycleResponse r = BookingLifecycleService.build(facts(b, 1, List.of(),
                List.of(bill(SupplierInvoiceStatus.DRAFT, "8000", "8000")), "0"), Viewer.OWNER);

        assertThat(r.getSteps().get(0).getSummary()).isEqualTo("Confirmed with IndiGo");
    }

    @Test
    void anUnpricedVoucherOnAPendingBookingIsNotDoneYet() {
        BookingLifecycleResponse r = BookingLifecycleService.build(facts(booking(BookingStatus.PENDING), 1, List.of(), List.of(), "0"), Viewer.OWNER);

        assertThat(states(r)).containsEntry("VOUCHER", "PARTIAL");
        assertThat(r.getSteps().get(1).getSummary()).contains("add the cost and markup");
    }

    /** Nothing left to collect - fully invoiced and paid, or already paid in advance - means no Record payment. */
    @Test
    void aFullyPaidBookingOffersNoRecordPayment() {
        Booking b = booking(BookingStatus.CONFIRMED);
        b.setInvoicedTotalInr(d("10000"));
        b.setReceivedTotalInr(d("10000"));
        BookingLifecycleResponse paid = BookingLifecycleService.build(new Facts(b, 1, List.of(issued("10000", "0")), List.of(), List.of(), List.of(),
                BigDecimal.ZERO, d("10000")), Viewer.OWNER);
        assertThat(paid.getToCollectInr()).isEqualByComparingTo("0");
        assertThat(paid.getAllowedActions()).doesNotContain("RECORD_PAYMENT");

        BookingLifecycleResponse paidAhead = BookingLifecycleService.build(new Facts(booking(BookingStatus.CONFIRMED), 1, List.of(), List.of(), List.of(), List.of(),
                d("10000"), BigDecimal.ZERO), Viewer.ACCOUNTANT);
        assertThat(paidAhead.getAllowedActions()).contains("GENERATE_INVOICE").doesNotContain("RECORD_PAYMENT");
    }

    @Test
    void invoicedAndPartlyPaidShowsWhatIsLeftToCollect() {
        Booking b = booking(BookingStatus.CONFIRMED);
        b.setInvoicedTotalInr(d("10000"));
        b.setReceivedTotalInr(d("4000"));
        BookingLifecycleResponse r = BookingLifecycleService.build(new Facts(b, 1, List.of(issued("10000", "6000")), List.of(), List.of(), List.of(),
                BigDecimal.ZERO, d("10000")), Viewer.OWNER);

        assertThat(states(r)).containsEntry("VOUCHER", "DONE").containsEntry("INVOICE", "DONE").containsEntry("PAYMENT", "PARTIAL");
        assertThat(r.getToCollectInr()).isEqualByComparingTo("6000");
        // Status moves by itself (voucher confirms, the nightly job completes) - no status buttons.
        assertThat(r.getAllowedActions()).doesNotContain("GENERATE_INVOICE", "CONFIRM", "COMPLETE").contains("RECORD_PAYMENT");
    }

    @Test
    void aVoucherAddedAfterTheInvoiceAsksForAnotherInvoice() {
        Booking b = booking(BookingStatus.CONFIRMED);
        b.setSellingPrice(d("13000"));
        b.setInvoicedTotalInr(d("10000"));
        BookingLifecycleResponse r = BookingLifecycleService.build(new Facts(b, 2, List.of(issued("10000", "0")), List.of(), List.of(), List.of(),
                BigDecimal.ZERO, d("10000")), Viewer.ACCOUNTANT);

        assertThat(states(r)).containsEntry("INVOICE", "PARTIAL");
        assertThat(r.getSteps().stream().filter(x -> x.getKey().equals("INVOICE")).findFirst().orElseThrow().getSummary())
                .contains("₹3,000 added since the last invoice");
        assertThat(r.getAllowedActions()).contains("GENERATE_INVOICE");
        // BUG-MO-07: the Generate invoice modal shows the 3,000 being invoiced, not the 13,000 booking price.
        assertThat(r.getUninvoicedInr()).isEqualByComparingTo("3000");
    }

    @Test
    void anAdvanceHeldOnAccountIsShownUntilTheInvoiceIsGenerated() {
        BookingLifecycleResponse r = BookingLifecycleService.build(facts(booking(BookingStatus.CONFIRMED), 0, List.of(), List.of(), "3000"), Viewer.OWNER);

        BookingLifecycleResponse.Step payment = r.getSteps().stream().filter(s -> s.getKey().equals("PAYMENT")).findFirst().orElseThrow();
        assertThat(payment.getSummary()).contains("held on account");
        assertThat(r.getClientWalletInr()).isEqualByComparingTo("3000");
        assertThat(r.getToCollectInr()).isEqualByComparingTo("7000");
    }

    @Test
    void cancelledBookingTracksBothRefundsThenTheCrossCheck() {
        Booking b = booking(BookingStatus.CANCELLED);
        b.setReceivedTotalInr(d("10000"));
        b.setCustomerRefundDue(d("7500"));
        b.setRefundedTotalInr(d("2500"));
        b.setSupplierRefundExpected(d("6000"));
        BookingLifecycleResponse open = BookingLifecycleService.build(facts(b, 1, List.of(), List.of(), "0"), Viewer.ACCOUNTANT);

        assertThat(states(open)).containsEntry("CANCELLED", "DONE").containsEntry("CLIENT_REFUND", "PARTIAL").containsEntry("SUPPLIER_REFUND", "OPEN");
        // The supplier's refund is the step before the client's.
        assertThat(open.getSteps()).extracting(BookingLifecycleResponse.Step::getKey).endsWith("CANCELLED", "SUPPLIER_REFUND", "CLIENT_REFUND");
        // The agent records the supplier refund, not Accounts.
        assertThat(open.getAllowedActions()).containsExactly("REFUND_CLIENT");
        assertThat(BookingLifecycleService.build(facts(b, 1, List.of(), List.of(), "0"), Viewer.AGENT).getAllowedActions())
                .containsExactly("SUPPLIER_REFUND");

        b.setRefundedTotalInr(d("7500"));
        b.setSupplierRefundReceived(d("6000"));
        BookingLifecycleResponse settled = BookingLifecycleService.build(facts(b, 1, List.of(), List.of(), "0"), Viewer.ACCOUNTANT);
        assertThat(states(settled)).containsEntry("CLIENT_REFUND", "DONE").containsEntry("SUPPLIER_REFUND", "DONE");
        assertThat(settled.getAllowedActions()).containsExactly("CROSS_CHECK");
    }

    /** BUG-MO-14 / MO-13, BK-01000: cancelled before any invoice, its 4,680 "Cancellation charges" invoice is unpaid. */
    @Test
    void noCrossCheckWhileTheCancellationChargesAreUnpaid() {
        Booking b = booking(BookingStatus.CANCELLED);
        b.setInvoicedTotalInr(d("4680"));
        BookingLifecycleResponse owed = BookingLifecycleService.build(facts(b, 1, List.of(issued("4680", "4680")), List.of(), "0"), Viewer.ACCOUNTANT);

        assertThat(owed.getToCollectInr()).isEqualByComparingTo("4680");
        assertThat(owed.getAllowedActions()).containsExactly("RECORD_PAYMENT");

        // Paid after the cancel: settled, so it can be cross-checked - and it is not called money "received before cancelling".
        b.setReceivedTotalInr(d("4680"));
        BookingLifecycleResponse paid = BookingLifecycleService.build(facts(b, 1, List.of(issued("4680", "0")), List.of(), "0"), Viewer.ACCOUNTANT);
        assertThat(paid.getAllowedActions()).containsExactly("CROSS_CHECK");
        assertThat(paid.getSteps().stream().filter(x -> x.getKey().equals("PAYMENT")).findFirst().orElseThrow().getSummary())
                .isEqualTo("₹4,680 received");
    }

    // ---- roles -------------------------------------------------------------------------

    @Test
    void agentGetsOperationalActionsAndNoSupplierMoney() {
        Facts f = new Facts(booking(BookingStatus.CONFIRMED), 0, List.of(), List.of(),
                List.of(bill(SupplierInvoiceStatus.PARTIALLY_PAID, "8000", "3000")),
                List.of(SupplierPayment.builder().id("P1").amountInr(d("5000")).paidOn(LocalDate.now()).build()), d("100"), BigDecimal.ZERO);
        BookingLifecycleResponse r = BookingLifecycleService.build(f, Viewer.AGENT);

        assertThat(r.getAllowedActions()).containsExactlyInAnyOrder("UPLOAD_VOUCHER", "CANCEL");
        assertThat(r.getClientWalletInr()).isNull();
        assertThat(r.getRecords()).noneMatch(x -> x.getKind().startsWith("SUPPLIER"));
    }

    @Test
    void agentSeesTheSupplierRefundAmountsTheyRecord() {
        Booking b = booking(BookingStatus.CANCELLED);
        b.setSupplierRefundExpected(d("7000"));
        BookingLifecycleResponse r = BookingLifecycleService.build(facts(b, 0, List.of(), List.of(), "0"), Viewer.AGENT);

        assertThat(r.getSteps().stream().filter(s -> s.getKey().equals("SUPPLIER_REFUND")).findFirst().orElseThrow().getSummary())
                .isEqualTo("₹0 of ₹7,000 received");
        assertThat(r.getSupplierRefundOutstandingInr()).isEqualByComparingTo("7000");
        assertThat(r.getRecords()).noneMatch(x -> x.getKind().startsWith("SUPPLIER"));
    }

    @Test
    void anOpenBankClaimWaitsForTheAccountant() {
        Booking b = booking(BookingStatus.CANCELLED);
        b.setSupplierRefundExpected(d("7000"));
        b.setSupplierRefundClaimAmount(d("7000"));
        b.setSupplierRefundClaimBy("Vikram");

        BookingLifecycleResponse agent = BookingLifecycleService.build(facts(b, 0, List.of(), List.of(), "0"), Viewer.AGENT);
        BookingLifecycleResponse accountant = BookingLifecycleService.build(facts(b, 0, List.of(), List.of(), "0"), Viewer.ACCOUNTANT);

        assertThat(agent.getAllowedActions()).doesNotContain("SUPPLIER_REFUND");
        assertThat(accountant.getAllowedActions()).containsExactlyInAnyOrder("CONFIRM_SUPPLIER_REFUND", "REJECT_SUPPLIER_REFUND");
        assertThat(accountant.getSupplierRefundClaim().getClaimedBy()).isEqualTo("Vikram");
        assertThat(accountant.getSteps().stream().filter(s -> s.getKey().equals("SUPPLIER_REFUND")).findFirst().orElseThrow().getSummary())
                .contains("₹7,000 claimed into bank");
    }

    @Test
    void accountantGetsMoneyActionsButCannotConfirmOrCancel() {
        BookingLifecycleResponse r = BookingLifecycleService.build(facts(booking(BookingStatus.PENDING), 0, List.of(), List.of(), "0"), Viewer.ACCOUNTANT);

        assertThat(r.getAllowedActions()).containsExactlyInAnyOrder("RECORD_PAYMENT");
    }

    // ---- actions -----------------------------------------------------------------------

    private Booking stubBooking(Booking b) {
        when(bookingService.findAccessibleBooking("B1")).thenReturn(b);
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(b));
        when(bookingRepository.findByIdForUpdate("B1")).thenReturn(Optional.of(b));
        org.mockito.Mockito.lenient().when(bookingService.invoicedPrice(any())).thenReturn(BigDecimal.ZERO);
        when(paymentReceiptService.availableWalletFor(any(), (String) any())).thenReturn(BigDecimal.ZERO);
        // Each invoice here bills only this booking.
        when(invoiceDocumentService.weightOf(any(), any())).thenReturn(BigDecimal.ONE);
        return b;
    }

    private static BookingPaymentRequest payment(String amount) {
        BookingPaymentRequest r = new BookingPaymentRequest();
        r.setAmount(d(amount));
        r.setPaymentMode(PaymentMode.UPI);
        r.setReceivedOn(LocalDate.of(2026, 10, 17));
        r.setReference("UTR1");
        return r;
    }



    @Test
    void paymentBeforeAnyInvoiceIsHeldOnTheClientsAccount() {
        stubBooking(booking(BookingStatus.CONFIRMED));

        service.recordPayment("B1", payment("4000"));

        ArgumentCaptor<PaymentReceiptRequest> receipt = ArgumentCaptor.forClass(PaymentReceiptRequest.class);
        verify(paymentReceiptService).record(receipt.capture());
        assertThat(receipt.getValue().getInvoiceId()).isNull();
        assertThat(receipt.getValue().getClientId()).isEqualTo("C1");
        assertThat(receipt.getValue().getNotes()).isEqualTo("Advance for BK00000007");
        assertThat(receipt.getValue().getBookingId()).as("held for this booking, not the client's others").isEqualTo("B1");
    }

    /** The client may pay the GST-inclusive total before the invoice exists: it is held for the booking, never refused. */
    @Test
    void aPaymentAboveTheBookingPriceBeforeTheInvoiceIsHeldAsAnAdvance() {
        stubBooking(booking(BookingStatus.CONFIRMED));
        org.mockito.Mockito.lenient().when(paymentReceiptService.availableWalletFor("C1", "B1")).thenReturn(d("4000"));

        service.recordPayment("B1", payment("11800"));

        ArgumentCaptor<PaymentReceiptRequest> receipt = ArgumentCaptor.forClass(PaymentReceiptRequest.class);
        verify(paymentReceiptService).record(receipt.capture());
        assertThat(receipt.getValue().getAmount()).isEqualByComparingTo("11800");
        assertThat(receipt.getValue().getInvoiceId()).isNull();
        assertThat(receipt.getValue().getBookingId()).isEqualTo("B1");
    }

    /** More than the invoice still owes (e.g. a voucher added after it): the invoice is settled, the rest held for the booking. */
    @Test
    void aPaymentAboveTheInvoiceBalanceSettlesItAndHoldsTheRest() {
        stubBooking(booking(BookingStatus.CONFIRMED));
        when(invoiceRepository.findByBookingIdAndDocumentTypeAndStatusNotOrderByCreatedAtAsc(eq("B1"), any(), any()))
                .thenReturn(List.of(issued("10000", "6000")));

        service.recordPayment("B1", payment("9000"));

        ArgumentCaptor<PaymentReceiptRequest> receipt = ArgumentCaptor.forClass(PaymentReceiptRequest.class);
        verify(paymentReceiptService, org.mockito.Mockito.times(2)).record(receipt.capture());
        assertThat(receipt.getAllValues().get(0).getInvoiceId()).isEqualTo("I1");
        assertThat(receipt.getAllValues().get(0).getAmount()).isEqualByComparingTo("6000");
        assertThat(receipt.getAllValues().get(1).getInvoiceId()).isNull();
        assertThat(receipt.getAllValues().get(1).getAmount()).isEqualByComparingTo("3000");
        assertThat(receipt.getAllValues().get(1).getBookingId()).isEqualTo("B1");
    }

    @Test
    void paymentAfterTheInvoiceSettlesIt() {
        stubBooking(booking(BookingStatus.CONFIRMED));
        when(invoiceRepository.findByBookingIdAndDocumentTypeAndStatusNotOrderByCreatedAtAsc(eq("B1"), any(), any()))
                .thenReturn(List.of(issued("10000", "6000")));

        service.recordPayment("B1", payment("6000"));

        ArgumentCaptor<PaymentReceiptRequest> receipt = ArgumentCaptor.forClass(PaymentReceiptRequest.class);
        verify(paymentReceiptService).record(receipt.capture());
        assertThat(receipt.getValue().getInvoiceId()).isEqualTo("I1");
    }

    /** Regression run 6, m2: a booking with two open invoices can be paid off in one go. */
    @Test
    void aPaymentIsSpreadAcrossOpenInvoicesOldestFirst() {
        stubBooking(booking(BookingStatus.CONFIRMED));
        Invoice first = issued("4000", "1000");
        Invoice second = issued("6000", "6000");
        second.setId("I2");
        when(invoiceRepository.findByBookingIdAndDocumentTypeAndStatusNotOrderByCreatedAtAsc(eq("B1"), any(), any()))
                .thenReturn(List.of(first, second));

        service.recordPayment("B1", payment("7000"));

        ArgumentCaptor<PaymentReceiptRequest> receipts = ArgumentCaptor.forClass(PaymentReceiptRequest.class);
        verify(paymentReceiptService, org.mockito.Mockito.times(2)).record(receipts.capture());
        assertThat(receipts.getAllValues()).extracting(PaymentReceiptRequest::getInvoiceId).containsExactly("I1", "I2");
        assertThat(receipts.getAllValues().get(0).getAmount()).isEqualByComparingTo("1000");
        assertThat(receipts.getAllValues().get(1).getAmount()).isEqualByComparingTo("6000");
    }

    @Test
    void paymentOnACancelledBookingIsRefused() {
        stubBooking(booking(BookingStatus.CANCELLED));

        assertThatThrownBy(() -> service.recordPayment("B1", payment("100"))).isInstanceOf(IllegalStateException.class);
    }

    /** Regression run 6, B2/B3: every money action takes the booking's row lock before reading anything. */

    @Test
    void moneyActionsLockTheBookingFirst() {
        stubBooking(booking(BookingStatus.CONFIRMED));

        service.recordPayment("B1", payment("100"));

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(bookingRepository, bookingService);
        order.verify(bookingRepository).findByIdForUpdate("B1");
        order.verify(bookingService).findAccessibleBooking("B1");
    }

    @Test
    void aRefundFromTheSupplierIsNotListedAsAPaymentToThem() {
        SupplierPayment refund = SupplierPayment.builder().id("P2").direction(com.voyra.crm.enums.SupplierPaymentDirection.REFUND_RECEIVED)
                .amountInr(d("3500")).paidOn(LocalDate.now()).build();
        Facts f = new Facts(booking(BookingStatus.CANCELLED), 0, List.of(), List.of(), List.of(), List.of(refund), BigDecimal.ZERO, BigDecimal.ZERO);

        assertThat(BookingLifecycleService.build(f, Viewer.ACCOUNTANT).getRecords())
                .extracting(BookingLifecycleResponse.MoneyRecord::getKind).containsExactly("SUPPLIER_REFUND");
    }

    /** Owner pass r2: a cancelled booking's money record shows the credit notes, so it is clear why money is owed back. */
    @Test
    void creditNotesAppearInTheMoneyRecordForEveryRole() {
        com.voyra.crm.entity.CreditNote cn = com.voyra.crm.entity.CreditNote.builder().id("C1").creditNoteNumber("CN/2026-27/0181")
                .status(com.voyra.crm.enums.CreditNoteStatus.ISSUED).invoiceId("I1").totalAmountInr(d("10270"))
                .noteDate(LocalDate.of(2026, 10, 19)).build();
        Facts f = new Facts(booking(BookingStatus.CANCELLED), 0, List.of(issued("12360", "-10270")), List.of(), List.of(), List.of(),
                BigDecimal.ZERO, BigDecimal.ZERO, List.of(cn));

        BookingLifecycleResponse.MoneyRecord row = BookingLifecycleService.build(f, Viewer.AGENT).getRecords().stream()
                .filter(r -> "CREDIT_NOTE".equals(r.getKind())).findFirst().orElseThrow();
        assertThat(row.getNumber()).isEqualTo("CN/2026-27/0181");
        assertThat(row.getAmountInr()).isEqualByComparingTo("10270");
        assertThat(row.getDate()).isEqualTo(LocalDate.of(2026, 10, 19));
        assertThat(row.getParentId()).isEqualTo("I1");
    }

    /** A 44,720 invoice for two bookings: this one's own account (its 22,360 less 11,180 paid for it) is what it owes. */
    @Test
    void aLiveBookingOnASharedInvoiceOwesWhatIsLeftOnItsOwnAccount() {
        Booking b = booking(BookingStatus.CONFIRMED);
        b.setInvoicedTotalInr(d("22360"));
        b.setReceivedTotalInr(d("11180"));
        b.setSellingPrice(d("22000"));
        Invoice combined = issued("44720", "4680");
        combined.setStatus(InvoiceLifecycle.PARTIALLY_PAID);
        Facts f = new Facts(b, 1, List.of(combined), List.of(), List.of(), List.of(), BigDecimal.ZERO, d("22000"),
                List.of(), java.util.Map.of("I1", d("11180")));

        assertThat(BookingLifecycleService.build(f, Viewer.ACCOUNTANT).getToCollectInr()).isEqualByComparingTo("11180");
    }

    @Test
    void receiptsAndBillsAppearAsMoneyRecordsForAccounts() {
        PaymentReceipt receipt = PaymentReceipt.builder().id("R1").direction(ReceiptDirection.RECEIPT).receiptNumber("RCPT/1")
                .amountInr(d("4000")).paymentMode(PaymentMode.UPI).receivedOn(LocalDate.of(2026, 10, 2)).invoiceId("I1").build();
        Facts f = new Facts(booking(BookingStatus.CONFIRMED), 0, List.of(issued("10000", "6000")), List.of(receipt),
                List.of(bill(SupplierInvoiceStatus.APPROVED, "8000", "8000")), List.of(), BigDecimal.ZERO, BigDecimal.ZERO);

        BookingLifecycleResponse r = BookingLifecycleService.build(f, Viewer.ACCOUNTANT);

        assertThat(r.getRecords()).extracting(BookingLifecycleResponse.MoneyRecord::getKind)
                .containsExactlyInAnyOrder("INVOICE", "RECEIPT", "SUPPLIER_BILL");
    }


    /** CRB-15: the extra invoice's line says what was added - the return ticket - not only "Additional services". */

    /** A draft left over after the first invoice may be for the old amount: issuing it would bill the booking twice. */


    /** Two PNRs before the first invoice: each is its own line, so the second leg is on the invoice too. */

    /** Lines that would not add up to the price, or a booking with travellers (lines per passenger), keep the usual lines. */


    @Test
    void theInvoiceStepOffersGenerateOnceConfirmed() {
        stubBooking(booking(BookingStatus.CONFIRMED));
        assertThat(service.getLifecycle("B1").getAllowedActions()).contains("GENERATE_INVOICE");
    }

    @Test
    void anAgentIsNeverOfferedTheInvoice() {
        Facts f = new Facts(booking(BookingStatus.CONFIRMED), 1, List.of(), List.of(), List.of(), List.of(), BigDecimal.ZERO, BigDecimal.ZERO);
        assertThat(BookingLifecycleService.build(f, Viewer.AGENT).getAllowedActions()).doesNotContain("GENERATE_INVOICE");
    }

    @Test
    void aCancelledBookingOwesOnlyWhatIsLeftOnItsOwnAccountOfASharedInvoice() {
        Invoice shared = issued("10000", "6000");
        shared.setBalanceDueInr(d("6000"));
        Facts f = new Facts(booking(BookingStatus.CANCELLED), 1, List.of(shared), List.of(), List.of(), List.of(), BigDecimal.ZERO,
                BigDecimal.ZERO, List.of(), java.util.Map.of(shared.getId(), new BigDecimal("1500")));
        assertThat(BookingLifecycleService.build(f, Viewer.ACCOUNTANT).getToCollectInr()).isEqualByComparingTo("1500");
    }
}
