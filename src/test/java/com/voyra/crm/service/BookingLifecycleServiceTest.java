package com.voyra.crm.service;

import com.voyra.crm.dto.BookingLifecycleResponse;
import com.voyra.crm.dto.BookingPaymentRequest;
import com.voyra.crm.dto.BookingSupplierPaymentRequest;
import com.voyra.crm.dto.InvoiceDraftRequest;
import com.voyra.crm.dto.InvoiceResponse;
import com.voyra.crm.dto.PaymentReceiptRequest;
import com.voyra.crm.dto.SupplierInvoiceResponse;
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
    @Mock private SupplierInvoiceService supplierInvoiceService;
    @Mock private SupplierPaymentService supplierPaymentService;
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
        return new Facts(b, docs, invoices, List.of(), bills, List.of(), d(wallet));
    }

    private static Map<String, String> states(BookingLifecycleResponse r) {
        return r.getSteps().stream().collect(Collectors.toMap(BookingLifecycleResponse.Step::getKey, BookingLifecycleResponse.Step::getState));
    }

    // ---- step states -------------------------------------------------------------------

    @Test
    void freshDraftBookingHasEverythingAheadOfIt() {
        BookingLifecycleResponse r = BookingLifecycleService.build(facts(booking(BookingStatus.PENDING), 0, List.of(), List.of(), "0"), Viewer.OWNER);

        assertThat(states(r)).containsEntry("CREATED", "DONE").containsEntry("VOUCHER", "OPEN").containsEntry("INVOICE", "OPEN")
                .containsEntry("PAYMENT", "OPEN").containsEntry("SUPPLIER", "OPEN").doesNotContainKey("CANCELLED");
        assertThat(r.getSteps().get(0).getSummary()).startsWith("Draft");
        assertThat(r.getAllowedActions()).contains("CONFIRM", "UPLOAD_VOUCHER", "CANCEL", "GENERATE_INVOICE", "RECORD_PAYMENT", "PAY_SUPPLIER");
        assertThat(r.getToCollectInr()).isEqualByComparingTo("10000");
        assertThat(r.getSupplierToPayInr()).isEqualByComparingTo("8000");
    }

    @Test
    void invoicedAndPartlyPaidShowsWhatIsLeftToCollect() {
        Booking b = booking(BookingStatus.CONFIRMED);
        b.setInvoicedTotalInr(d("10000"));
        b.setReceivedTotalInr(d("4000"));
        BookingLifecycleResponse r = BookingLifecycleService.build(facts(b, 1, List.of(issued("10000", "6000")), List.of(), "0"), Viewer.OWNER);

        assertThat(states(r)).containsEntry("VOUCHER", "DONE").containsEntry("INVOICE", "DONE").containsEntry("PAYMENT", "PARTIAL");
        assertThat(r.getToCollectInr()).isEqualByComparingTo("6000");
        assertThat(r.getAllowedActions()).doesNotContain("GENERATE_INVOICE", "CONFIRM").contains("COMPLETE", "RECORD_PAYMENT");
    }

    @Test
    void anAdvanceHeldOnAccountIsShownUntilTheInvoiceIsGenerated() {
        BookingLifecycleResponse r = BookingLifecycleService.build(facts(booking(BookingStatus.CONFIRMED), 0, List.of(), List.of(), "3000"), Viewer.OWNER);

        BookingLifecycleResponse.Step payment = r.getSteps().stream().filter(s -> s.getKey().equals("PAYMENT")).findFirst().orElseThrow();
        assertThat(payment.getSummary()).contains("held on account");
        assertThat(r.getClientWalletInr()).isEqualByComparingTo("3000");
        assertThat(r.getToCollectInr()).isEqualByComparingTo("7000");
    }

    private static SupplierPayment paid(String amount) {
        return SupplierPayment.builder().id("P" + amount).amountInr(d(amount)).paidOn(LocalDate.now())
                .direction(com.voyra.crm.enums.SupplierPaymentDirection.PAYMENT).build();
    }

    @Test
    void supplierStepFollowsWhatWasActuallyPaidAgainstNetCost() {
        Booking b = booking(BookingStatus.CONFIRMED);
        BookingLifecycleResponse part = BookingLifecycleService.build(new Facts(b, 0, List.of(), List.of(),
                List.of(bill(SupplierInvoiceStatus.PARTIALLY_PAID, "8000", "3000")), List.of(paid("5000")), BigDecimal.ZERO), Viewer.OWNER);
        BookingLifecycleResponse full = BookingLifecycleService.build(new Facts(b, 0, List.of(), List.of(),
                List.of(bill(SupplierInvoiceStatus.PAID, "8000", "0")), List.of(paid("5000"), paid("3000")), BigDecimal.ZERO), Viewer.OWNER);

        assertThat(states(part)).containsEntry("SUPPLIER", "PARTIAL");
        assertThat(part.getSupplierToPayInr()).isEqualByComparingTo("3000");
        assertThat(states(full)).containsEntry("SUPPLIER", "DONE");
        assertThat(full.getAllowedActions()).doesNotContain("PAY_SUPPLIER");
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
        assertThat(open.getAllowedActions()).containsExactlyInAnyOrder("REFUND_CLIENT", "SUPPLIER_REFUND");

        b.setRefundedTotalInr(d("7500"));
        b.setSupplierRefundReceived(d("6000"));
        BookingLifecycleResponse settled = BookingLifecycleService.build(facts(b, 1, List.of(), List.of(), "0"), Viewer.ACCOUNTANT);
        assertThat(states(settled)).containsEntry("CLIENT_REFUND", "DONE").containsEntry("SUPPLIER_REFUND", "DONE");
        assertThat(settled.getAllowedActions()).containsExactly("CROSS_CHECK");
    }

    /** Regression run 6, M3: a supplier credit note leaves a negative bill balance - that is not money paid. */
    @Test
    void aSupplierCreditNoteIsNotCountedAsPayment() {
        Booking b = booking(BookingStatus.CONFIRMED);
        BookingLifecycleResponse r = BookingLifecycleService.build(new Facts(b, 0, List.of(), List.of(),
                List.of(bill(SupplierInvoiceStatus.PAID, "8000", "-7000")), List.of(paid("8000")), BigDecimal.ZERO), Viewer.OWNER);

        assertThat(r.getSteps().stream().filter(s -> s.getKey().equals("SUPPLIER")).findFirst().orElseThrow().getSummary())
                .startsWith("₹8,000 paid");
    }

    // ---- roles -------------------------------------------------------------------------

    @Test
    void agentGetsOperationalActionsAndNoSupplierMoney() {
        Facts f = new Facts(booking(BookingStatus.CONFIRMED), 0, List.of(), List.of(),
                List.of(bill(SupplierInvoiceStatus.PARTIALLY_PAID, "8000", "3000")),
                List.of(SupplierPayment.builder().id("P1").amountInr(d("5000")).paidOn(LocalDate.now()).build()), d("100"));
        BookingLifecycleResponse r = BookingLifecycleService.build(f, Viewer.AGENT);

        assertThat(r.getAllowedActions()).containsExactlyInAnyOrder("COMPLETE", "UPLOAD_VOUCHER", "CANCEL");
        assertThat(r.getSupplierToPayInr()).isNull();
        assertThat(r.getClientWalletInr()).isNull();
        assertThat(r.getRecords()).noneMatch(x -> x.getKind().startsWith("SUPPLIER"));
        assertThat(r.getSteps().stream().filter(s -> s.getKey().equals("SUPPLIER")).findFirst().orElseThrow().getSummary())
                .doesNotContain("₹");
    }

    /** Regression run 6, m1: agents see whether the supplier refund arrived, not how much. */
    @Test
    void agentSeesNoSupplierRefundAmounts() {
        Booking b = booking(BookingStatus.CANCELLED);
        b.setSupplierRefundExpected(d("7000"));
        b.setSupplierRefundReceived(d("7000"));
        BookingLifecycleResponse r = BookingLifecycleService.build(facts(b, 0, List.of(), List.of(), "0"), Viewer.AGENT);

        assertThat(r.getSteps().stream().filter(s -> s.getKey().equals("SUPPLIER_REFUND")).findFirst().orElseThrow().getSummary())
                .isEqualTo("Received").doesNotContain("₹");
    }

    @Test
    void accountantGetsMoneyActionsButCannotConfirmOrCancel() {
        BookingLifecycleResponse r = BookingLifecycleService.build(facts(booking(BookingStatus.PENDING), 0, List.of(), List.of(), "0"), Viewer.ACCOUNTANT);

        assertThat(r.getAllowedActions()).containsExactlyInAnyOrder("GENERATE_INVOICE", "RECORD_PAYMENT", "PAY_SUPPLIER");
    }

    // ---- actions -----------------------------------------------------------------------

    private Booking stubBooking(Booking b) {
        when(bookingService.findAccessibleBooking("B1")).thenReturn(b);
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(b));
        when(bookingRepository.findByIdForUpdate("B1")).thenReturn(Optional.of(b));
        when(paymentReceiptService.availableWalletFor(any(), any())).thenReturn(BigDecimal.ZERO);
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
    void generateInvoiceCreatesAndIssuesFromTheBooking() {
        stubBooking(booking(BookingStatus.CONFIRMED));
        when(invoiceDocumentService.createDraft(any())).thenReturn(InvoiceResponse.builder().id("I9").build());

        service.generateInvoice("B1", null);

        ArgumentCaptor<InvoiceDraftRequest> draft = ArgumentCaptor.forClass(InvoiceDraftRequest.class);
        verify(invoiceDocumentService).createDraft(draft.capture());
        assertThat(draft.getValue().getBookingId()).isEqualTo("B1");
        verify(invoiceDocumentService).issue("I9");
    }

    @Test
    void generateInvoiceIsRefusedWhenOneIsAlreadyIssued() {
        stubBooking(booking(BookingStatus.CONFIRMED));
        when(invoiceRepository.findByBookingIdAndDocumentTypeAndStatusNotOrderByCreatedAtAsc(eq("B1"), any(), any()))
                .thenReturn(List.of(issued("10000", "10000")));

        assertThatThrownBy(() -> service.generateInvoice("B1", null)).isInstanceOf(IllegalStateException.class);
        verify(invoiceDocumentService, never()).issue(anyString());
    }

    @Test
    void paymentBeforeAnyInvoiceIsHeldOnTheClientsAccount() {
        stubBooking(booking(BookingStatus.CONFIRMED));

        service.recordPayment("B1", payment("4000"));

        ArgumentCaptor<PaymentReceiptRequest> receipt = ArgumentCaptor.forClass(PaymentReceiptRequest.class);
        verify(paymentReceiptService).record(receipt.capture());
        assertThat(receipt.getValue().getInvoiceId()).isNull();
        assertThat(receipt.getValue().getClientId()).isEqualTo("C1");
        assertThat(receipt.getValue().getNotes()).isEqualTo("Advance for BK-00007");
        assertThat(receipt.getValue().getBookingId()).as("held for this booking, not the client's others").isEqualTo("B1");
    }

    @Test
    void paymentBeforeInvoiceCannotExceedTheBookingPrice() {
        stubBooking(booking(BookingStatus.CONFIRMED));

        assertThatThrownBy(() -> service.recordPayment("B1", payment("10000.01"))).isInstanceOf(IllegalArgumentException.class);
        verify(paymentReceiptService, never()).record(any());
    }

    @Test
    void anAdvanceAlreadyHeldCountsAgainstWhatCanStillBeCollected() {
        stubBooking(booking(BookingStatus.CONFIRMED));
        when(paymentReceiptService.availableWalletFor("C1", "B1")).thenReturn(d("4000"));

        assertThatThrownBy(() -> service.recordPayment("B1", payment("6000.01"))).isInstanceOf(IllegalArgumentException.class);
        verify(paymentReceiptService, never()).record(any());
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

    private static BookingSupplierPaymentRequest supplierPay(String vendorId) {
        BookingSupplierPaymentRequest r = new BookingSupplierPaymentRequest();
        r.setVendorId(vendorId);
        r.setAmount(d("8000"));
        r.setPaymentMode(PaymentMode.BANK_TRANSFER);
        r.setPaidOn(LocalDate.of(2026, 10, 17));
        r.setSupplierInvoiceNumber("INV-2231");
        return r;
    }

    @Test
    void payingTheSupplierDraftsApprovesAndPaysTheBill() {
        Booking b = stubBooking(booking(BookingStatus.CONFIRMED));
        SupplierInvoice drafted = bill(SupplierInvoiceStatus.DRAFT, "8000", "8000");
        when(supplierInvoiceService.createAutoDraft(b, "V1")).thenReturn(List.of(SupplierInvoiceResponse.builder().id("S1").vendorId("V1").build()));
        when(supplierInvoiceRepository.findById("S1")).thenReturn(Optional.of(drafted));
        when(supplierInvoiceService.approve("S1", null)).thenReturn(SupplierInvoiceResponse.builder().id("S1").status(SupplierInvoiceStatus.APPROVED).build());

        service.paySupplier("B1", supplierPay("V1"));

        assertThat(b.getVendorId()).isEqualTo("V1");
        assertThat(drafted.getSupplierInvoiceNumber()).isEqualTo("INV-2231");
        ArgumentCaptor<SupplierPaymentRequest> pay = ArgumentCaptor.forClass(SupplierPaymentRequest.class);
        verify(supplierPaymentService).pay(pay.capture());
        assertThat(pay.getValue().getSupplierInvoiceId()).isEqualTo("S1");
        assertThat(pay.getValue().getVendorId()).isEqualTo("V1");
    }

    @Test
    void anExistingAutoDraftIsReusedNotDuplicated() {
        Booking b = booking(BookingStatus.CONFIRMED);
        b.setVendorId("V1");
        stubBooking(b);
        when(supplierInvoiceRepository.findByBookingIdAndStatusNot("B1", SupplierInvoiceStatus.CANCELLED))
                .thenReturn(List.of(bill(SupplierInvoiceStatus.DRAFT, "8000", "8000")));
        when(supplierInvoiceService.approve("S1", null)).thenReturn(SupplierInvoiceResponse.builder().status(SupplierInvoiceStatus.APPROVED).build());

        service.paySupplier("B1", supplierPay(null));

        verify(supplierInvoiceService, never()).createAutoDraft(any(), anyString());
        verify(supplierPaymentService).pay(any());
    }

    @Test
    void aBillAboveTheCostCapWithoutOverrideIsRefusedAndNothingIsPaid() {
        Booking b = booking(BookingStatus.CONFIRMED);
        b.setVendorId("V1");
        stubBooking(b);
        when(supplierInvoiceRepository.findByBookingIdAndStatusNot("B1", SupplierInvoiceStatus.CANCELLED))
                .thenReturn(List.of(bill(SupplierInvoiceStatus.DRAFT, "9000", "9000")));
        when(supplierInvoiceService.approve("S1", null)).thenReturn(SupplierInvoiceResponse.builder().status(SupplierInvoiceStatus.PENDING_APPROVAL).build());

        assertThatThrownBy(() -> service.paySupplier("B1", supplierPay(null)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("owner");
        verify(supplierPaymentService, never()).pay(any());
    }

    /** Regression run 6, B4. */
    @Test
    void payingTheSupplierOnACancelledBookingIsRefused() {
        stubBooking(booking(BookingStatus.CANCELLED));

        assertThatThrownBy(() -> service.paySupplier("B1", supplierPay("V1"))).isInstanceOf(IllegalStateException.class);
        verify(supplierInvoiceService, never()).createAutoDraft(any(), anyString());
        verify(supplierPaymentService, never()).pay(any());
    }

    /** Regression run 6, B2/B3: every money action takes the booking's row lock before reading anything. */
    @Test
    void moneyActionsLockTheBookingFirst() {
        stubBooking(booking(BookingStatus.CONFIRMED));
        when(invoiceDocumentService.createDraft(any())).thenReturn(InvoiceResponse.builder().id("I9").build());

        service.generateInvoice("B1", null);
        service.recordPayment("B1", payment("100"));

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(bookingRepository, bookingService);
        order.verify(bookingRepository).findByIdForUpdate("B1");
        order.verify(bookingService).findAccessibleBooking("B1");
        verify(bookingRepository, org.mockito.Mockito.times(2)).findByIdForUpdate("B1");
    }

    @Test
    void payingWithNoSupplierKnownAsksForOne() {
        stubBooking(booking(BookingStatus.CONFIRMED));

        assertThatThrownBy(() -> service.paySupplier("B1", supplierPay(null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("supplier");
    }

    @Test
    void aRefundFromTheSupplierIsNotListedAsAPaymentToThem() {
        SupplierPayment refund = SupplierPayment.builder().id("P2").direction(com.voyra.crm.enums.SupplierPaymentDirection.REFUND_RECEIVED)
                .amountInr(d("3500")).paidOn(LocalDate.now()).build();
        Facts f = new Facts(booking(BookingStatus.CANCELLED), 0, List.of(), List.of(), List.of(), List.of(refund), BigDecimal.ZERO);

        assertThat(BookingLifecycleService.build(f, Viewer.ACCOUNTANT).getRecords())
                .extracting(BookingLifecycleResponse.MoneyRecord::getKind).containsExactly("SUPPLIER_REFUND");
    }

    @Test
    void receiptsAndBillsAppearAsMoneyRecordsForAccounts() {
        PaymentReceipt receipt = PaymentReceipt.builder().id("R1").direction(ReceiptDirection.RECEIPT).receiptNumber("RCPT/1")
                .amountInr(d("4000")).paymentMode(PaymentMode.UPI).receivedOn(LocalDate.of(2026, 10, 2)).invoiceId("I1").build();
        Facts f = new Facts(booking(BookingStatus.CONFIRMED), 0, List.of(issued("10000", "6000")), List.of(receipt),
                List.of(bill(SupplierInvoiceStatus.APPROVED, "8000", "8000")), List.of(), BigDecimal.ZERO);

        BookingLifecycleResponse r = BookingLifecycleService.build(f, Viewer.ACCOUNTANT);

        assertThat(r.getRecords()).extracting(BookingLifecycleResponse.MoneyRecord::getKind)
                .containsExactlyInAnyOrder("INVOICE", "RECEIPT", "SUPPLIER_BILL");
    }
}
