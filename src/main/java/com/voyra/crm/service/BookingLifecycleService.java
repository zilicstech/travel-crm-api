package com.voyra.crm.service;

import com.voyra.crm.dto.BookingInvoiceRequest;
import com.voyra.crm.dto.BookingLifecycleResponse;
import com.voyra.crm.dto.BookingLifecycleResponse.MoneyRecord;
import com.voyra.crm.dto.BookingLifecycleResponse.Step;
import com.voyra.crm.dto.BookingPaymentRequest;
import com.voyra.crm.dto.InvoiceDraftRequest;
import com.voyra.crm.dto.PaymentReceiptRequest;
import com.voyra.crm.entity.Booking;
import com.voyra.crm.entity.Invoice;
import com.voyra.crm.entity.PaymentReceipt;
import com.voyra.crm.entity.SupplierInvoice;
import com.voyra.crm.entity.SupplierPayment;
import com.voyra.crm.enums.BookingStatus;
import com.voyra.crm.enums.InvoiceDocumentType;
import com.voyra.crm.enums.InvoiceLifecycle;
import com.voyra.crm.enums.ReceiptDirection;
import com.voyra.crm.enums.SupplierInvoiceStatus;
import com.voyra.crm.enums.SupplierPaymentDirection;
import com.voyra.crm.repository.BookingDocumentRepository;
import com.voyra.crm.repository.BookingRepository;
import com.voyra.crm.repository.InvoiceRepository;
import com.voyra.crm.repository.PaymentReceiptRepository;
import com.voyra.crm.repository.SupplierInvoiceRepository;
import com.voyra.crm.repository.SupplierPaymentRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import com.voyra.crm.security.SecurityContextUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * The booking lifecycle from the client's booking-management flow: create (pending) -> voucher ->
 * invoice -> payment received, and after a cancel, refund to the client and refund from the
 * supplier. Paying the supplier is Accounts' job on the supplier bill, not a booking step. Reads every step's state from the real accounting records, and runs
 * the money steps as one-click actions by orchestrating the existing accounting services - it
 * never writes a ledger or journal row itself, so the books stay exactly as those services keep
 * them.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BookingLifecycleService {

    static final String CONFIRM = "CONFIRM";
    static final String COMPLETE = "COMPLETE";
    static final String UPLOAD_VOUCHER = "UPLOAD_VOUCHER";
    static final String CANCEL = "CANCEL";
    static final String GENERATE_INVOICE = "GENERATE_INVOICE";
    static final String RECORD_PAYMENT = "RECORD_PAYMENT";
    static final String REFUND_CLIENT = "REFUND_CLIENT";
    static final String SUPPLIER_REFUND = "SUPPLIER_REFUND";
    static final String CROSS_CHECK = "CROSS_CHECK";

    enum Viewer { OWNER, AGENT, ACCOUNTANT }

    /** Everything the steps are computed from, gathered once so the computation itself stays pure. */
    record Facts(Booking booking, int documentCount, List<Invoice> invoices, List<PaymentReceipt> receipts,
                 List<SupplierInvoice> bills, List<SupplierPayment> supplierPayments, BigDecimal clientWalletInr) {
    }

    private final BookingService bookingService;
    private final BookingRepository bookingRepository;
    private final BookingDocumentRepository bookingDocumentRepository;
    private final InvoiceRepository invoiceRepository;
    private final PaymentReceiptRepository paymentReceiptRepository;
    private final SupplierInvoiceRepository supplierInvoiceRepository;
    private final SupplierPaymentRepository supplierPaymentRepository;
    private final InvoiceDocumentService invoiceDocumentService;
    private final PaymentReceiptService paymentReceiptService;

    @Transactional(readOnly = true)
    public BookingLifecycleResponse getLifecycle(String bookingId) {
        Booking booking = bookingService.findAccessibleBooking(bookingId);
        return build(gather(booking), currentViewer());
    }

    /**
     * Generates and issues the booking's tax invoice in one step. A draft someone already started
     * is issued as it is; a booking that already has a live invoice is refused. Issuing applies
     * any money the client holds on account, so a payment taken before the invoice settles it.
     */
    @Transactional
    public BookingLifecycleResponse generateInvoice(String bookingId, BookingInvoiceRequest request) {
        Booking booking = lockAccessible(bookingId);
        List<Invoice> live = liveTaxInvoices(booking.getId());
        Invoice draft = live.stream().filter(i -> i.getStatus() == InvoiceLifecycle.DRAFT).findFirst().orElse(null);
        if (draft == null && !live.isEmpty()) {
            throw new IllegalStateException("This booking already has an invoice");
        }
        String invoiceId = draft != null ? draft.getId() : invoiceDocumentService.createDraft(draftRequest(booking, request)).getId();
        invoiceDocumentService.issue(invoiceId);
        log.info("Booking invoice generated: bookingId={}, invoiceId={}", booking.getId(), invoiceId);
        return build(gather(reload(booking)), currentViewer());
    }

    /**
     * Records money received from the client. With an invoice that still has a balance, the
     * receipt settles it; before any invoice exists, the money is held on the client's account
     * (the customer wallet) and is applied automatically when the invoice is generated.
     */
    @Transactional
    public BookingLifecycleResponse recordPayment(String bookingId, BookingPaymentRequest request) {
        Booking booking = lockAccessible(bookingId);
        if (booking.getBookingStatus() == BookingStatus.CANCELLED) {
            throw new IllegalStateException("This booking is cancelled - record refunds instead");
        }
        List<Invoice> issued = liveTaxInvoices(booking.getId()).stream()
                .filter(i -> i.getStatus() != InvoiceLifecycle.DRAFT).toList();

        if (issued.isEmpty()) {
            BigDecimal open = nz(booking.getSellingPrice()).subtract(nz(booking.getReceivedTotalInr()))
                    .subtract(paymentReceiptService.availableWalletFor(booking.getClientId(), booking.getId()));
            if (request.getAmount().compareTo(open) > 0) {
                throw new IllegalArgumentException("More than the " + open + " still to collect on this booking");
            }
            PaymentReceiptRequest receipt = receiptRequest(request, request.getAmount());
            receipt.setClientId(booking.getClientId());
            receipt.setBookingId(booking.getId());
            receipt.setNotes("Advance for " + BookingService.bookingRef(booking.getBookingNumber()));
            paymentReceiptService.record(receipt);
        } else {
            // Spread across the booking's open invoices, oldest first, so the full amount shown as due can be paid at once.
            List<Invoice> open = issued.stream().filter(i -> nz(i.getBalanceDue()).signum() > 0).toList();
            BigDecimal due = open.stream().map(i -> nz(i.getBalanceDue())).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (due.signum() == 0) {
                throw new IllegalStateException("This booking's invoice is fully paid");
            }
            if (request.getAmount().compareTo(due) > 0) {
                throw new IllegalArgumentException("More than the " + due + " still due on this booking's invoices");
            }
            BigDecimal left = request.getAmount();
            for (Invoice invoice : open) {
                if (left.signum() == 0) break;
                BigDecimal part = left.min(nz(invoice.getBalanceDue()));
                PaymentReceiptRequest r = receiptRequest(request, part);
                r.setInvoiceId(invoice.getId());
                paymentReceiptService.record(r);
                left = left.subtract(part);
            }
        }
        return build(gather(reload(booking)), currentViewer());
    }

    private static PaymentReceiptRequest receiptRequest(BookingPaymentRequest request, BigDecimal amount) {
        PaymentReceiptRequest r = new PaymentReceiptRequest();
        r.setAmount(amount);
        r.setPaymentMode(request.getPaymentMode());
        r.setReceivedOn(request.getReceivedOn());
        r.setInstrumentRef(blankToNull(request.getReference()));
        return r;
    }

    /**
     * Locks the booking row first (a second concurrent call waits here, then sees what the first
     * one wrote), then applies the usual access check to that same, freshly read booking.
     */
    private Booking lockAccessible(String bookingId) {
        bookingRepository.findByIdForUpdate(bookingId);
        return bookingService.findAccessibleBooking(bookingId);
    }

    private List<Invoice> liveTaxInvoices(String bookingId) {
        return invoiceRepository.findByBookingIdAndDocumentTypeAndStatusNotOrderByCreatedAtAsc(
                bookingId, InvoiceDocumentType.TAX_INVOICE, InvoiceLifecycle.CANCELLED);
    }

    private static InvoiceDraftRequest draftRequest(Booking booking, BookingInvoiceRequest request) {
        InvoiceDraftRequest draft = new InvoiceDraftRequest();
        draft.setBookingId(booking.getId());
        if (request != null) {
            draft.setServiceCategory(request.getServiceCategory());
            draft.setDueDate(request.getDueDate());
            draft.setNotes(blankToNull(request.getNotes()));
        }
        return draft;
    }

    private Booking reload(Booking booking) {
        return bookingRepository.findById(booking.getId()).orElse(booking);
    }

    Facts gather(Booking booking) {
        String id = booking.getId();
        List<PaymentReceipt> receipts = new ArrayList<>(paymentReceiptRepository.findByBookingIdAndDirection(id, ReceiptDirection.RECEIPT));
        receipts.addAll(paymentReceiptRepository.findByBookingIdAndDirection(id, ReceiptDirection.REFUND));
        List<SupplierInvoice> bills = supplierInvoiceRepository.findByBookingIdAndStatusNot(id, SupplierInvoiceStatus.CANCELLED);
        List<SupplierPayment> payments = supplierPaymentRepository.findByBookingId(id);
        return new Facts(booking, bookingDocumentRepository.findByBookingIdOrderBySortOrderAsc(id).size(),
                invoiceRepository.findByBookingId(id), receipts, bills, payments,
                paymentReceiptService.availableWalletFor(booking.getClientId(), booking.getId()));
    }

    private static Viewer currentViewer() {
        CustomUserPrincipal principal = SecurityContextUtil.getCurrentUserOrThrow();
        return principal.isAgent() ? Viewer.AGENT : principal.isAccountant() ? Viewer.ACCOUNTANT : Viewer.OWNER;
    }

    /** Pure: the whole response from the gathered facts and who is asking. */
    static BookingLifecycleResponse build(Facts f, Viewer viewer) {
        Booking b = f.booking();
        BookingStatus status = b.getBookingStatus();
        boolean cancelled = status == BookingStatus.CANCELLED;
        boolean live = status == BookingStatus.PENDING || status == BookingStatus.CONFIRMED;
        boolean ops = viewer != Viewer.ACCOUNTANT;
        boolean accounts = viewer != Viewer.AGENT;

        List<Invoice> liveInvoices = f.invoices().stream()
                .filter(i -> i.getDocumentType() == InvoiceDocumentType.TAX_INVOICE && i.getStatus() != InvoiceLifecycle.CANCELLED)
                .toList();
        boolean invoiced = liveInvoices.stream().anyMatch(i -> i.getStatus() != InvoiceLifecycle.DRAFT);
        BigDecimal invoicedTotal = nz(b.getInvoicedTotalInr());
        BigDecimal received = nz(b.getReceivedTotalInr());
        // Before the invoice, money already held on the client's account will be applied to it, so it is not asked for again.
        BigDecimal toCollect = cancelled ? BigDecimal.ZERO
                : (invoiced ? invoicedTotal.subtract(received) : nz(b.getSellingPrice()).subtract(received).subtract(nz(f.clientWalletInr())))
                        .max(BigDecimal.ZERO);

        BigDecimal refundDue = nz(b.getCustomerRefundDue());
        BigDecimal refunded = nz(b.getRefundedTotalInr());
        BigDecimal refundExpected = nz(b.getSupplierRefundExpected());
        BigDecimal refundReceived = nz(b.getSupplierRefundReceived());
        boolean clientRefundOpen = refunded.compareTo(refundDue) < 0;
        boolean supplierRefundOpen = refundReceived.compareTo(refundExpected) < 0;

        List<Step> steps = new ArrayList<>();
        String with = b.getSupplier() != null && !b.getSupplier().isBlank() ? " with " + b.getSupplier() : "";
        steps.add(step("CREATED", "DONE", status == BookingStatus.PENDING ? "Pending - waiting for supplier confirmation"
                : cancelled ? "Booked" + with : (status == BookingStatus.COMPLETED ? "Completed" : "Confirmed") + with));
        steps.add(step("VOUCHER", f.documentCount() > 0 ? "DONE" : cancelled ? "NA" : "OPEN",
                f.documentCount() > 0 ? f.documentCount() + " document" + (f.documentCount() == 1 ? "" : "s") + " uploaded" : "No voucher uploaded yet"));
        steps.add(step("INVOICE", invoiced ? "DONE" : cancelled ? "NA" : "OPEN",
                invoiced ? "Invoiced " + money(invoicedTotal) : liveInvoices.isEmpty() ? "Not invoiced yet" : "Draft invoice not issued yet"));
        steps.add(paymentStep(cancelled, invoiced, received, toCollect, f.clientWalletInr()));
        if (cancelled) {
            steps.add(step("CANCELLED", "DONE", "Cancelled"));
            steps.add(step("CLIENT_REFUND", refundDue.signum() == 0 ? "NA" : clientRefundOpen ? (refunded.signum() > 0 ? "PARTIAL" : "OPEN") : "DONE",
                    refundDue.signum() == 0 ? "Nothing to refund" : money(refunded) + " of " + money(refundDue) + " refunded"));
            String supplierRefundState = refundExpected.signum() == 0 ? "NA" : supplierRefundOpen ? (refundReceived.signum() > 0 ? "PARTIAL" : "OPEN") : "DONE";
            String supplierRefundSummary = refundExpected.signum() == 0 ? "Nothing expected"
                    : accounts ? money(refundReceived) + " of " + money(refundExpected) + " received"
                    : supplierRefundOpen ? "Waiting for the supplier" : "Received";
            steps.add(step("SUPPLIER_REFUND", supplierRefundState,
                    supplierRefundSummary + (b.getRefundCheckedAt() != null ? " - cross-checked" : "")));
        }

        List<String> actions = new ArrayList<>();
        if (ops && status == BookingStatus.PENDING) actions.add(CONFIRM);
        if (ops && status == BookingStatus.CONFIRMED) actions.add(COMPLETE);
        if (ops && !cancelled) actions.add(UPLOAD_VOUCHER);
        if (ops && live) actions.add(CANCEL);
        if (accounts && !cancelled && !invoiced && nz(b.getSellingPrice()).signum() > 0) actions.add(GENERATE_INVOICE);
        if (accounts && !cancelled && toCollect.signum() > 0) actions.add(RECORD_PAYMENT);
        if (accounts && cancelled && clientRefundOpen) actions.add(REFUND_CLIENT);
        if (accounts && cancelled && supplierRefundOpen) actions.add(SUPPLIER_REFUND);
        if (accounts && cancelled && !clientRefundOpen && !supplierRefundOpen && b.getRefundCheckedAt() == null) actions.add(CROSS_CHECK);

        return BookingLifecycleResponse.builder()
                .bookingId(b.getId())
                .steps(steps)
                .allowedActions(actions)
                .records(records(f, accounts))
                .toCollectInr(toCollect)
                .clientWalletInr(accounts ? f.clientWalletInr() : null)
                .build();
    }

    private static Step paymentStep(boolean cancelled, boolean invoiced, BigDecimal received, BigDecimal toCollect, BigDecimal wallet) {
        if (cancelled) {
            if (received.signum() == 0 && nz(wallet).signum() > 0) {
                return step("PAYMENT", "DONE", money(wallet) + " held as an advance before cancelling");
            }
            return step("PAYMENT", received.signum() > 0 ? "DONE" : "NA", money(received) + " received before cancelling");
        }
        if (toCollect.signum() == 0 && received.signum() > 0) {
            return step("PAYMENT", "DONE", "Fully paid - " + money(received));
        }
        String held = !invoiced && nz(wallet).signum() > 0 ? " · " + money(wallet) + " held on account, applied on invoice" : "";
        return step("PAYMENT", received.signum() > 0 ? "PARTIAL" : "OPEN",
                (received.signum() > 0 ? money(received) + " received, " : "") + money(toCollect) + " to collect" + held);
    }

    private static List<MoneyRecord> records(Facts f, boolean withSupplier) {
        List<MoneyRecord> rows = new ArrayList<>();
        for (Invoice i : f.invoices()) {
            if (i.getDocumentType() != InvoiceDocumentType.TAX_INVOICE || i.getStatus() == InvoiceLifecycle.DRAFT) continue;
            rows.add(MoneyRecord.builder().id(i.getId()).kind("INVOICE").number(i.getInvoiceNumber()).status(i.getStatus().name())
                    .date(i.getInvoiceDate()).amountInr(i.getGrandTotalInr()).balanceInr(i.getBalanceDueInr()).build());
        }
        for (PaymentReceipt r : f.receipts()) {
            String kind = r.getDirection() == ReceiptDirection.REFUND ? "REFUND"
                    : r.getInvoiceId() == null && Boolean.TRUE.equals(r.getIsAdvance()) ? "ADVANCE"
                    : Boolean.TRUE.equals(r.getAppliedFromAdvance()) ? "ADVANCE_APPLIED" : "RECEIPT";
            rows.add(MoneyRecord.builder().id(r.getId()).kind(kind)
                    .number(r.getReceiptNumber()).date(r.getReceivedOn()).amountInr(r.getAmountInr())
                    .mode(r.getPaymentMode() != null ? r.getPaymentMode().name() : null)
                    .reversed(r.getReversedAt() != null || r.getReversesReceiptId() != null).parentId(r.getInvoiceId()).build());
        }
        if (withSupplier) {
            for (SupplierInvoice s : f.bills()) {
                rows.add(MoneyRecord.builder().id(s.getId()).kind("SUPPLIER_BILL").number(s.getSupplierInvoiceNumber())
                        .status(s.getStatus().name()).date(s.getInvoiceDate()).amountInr(s.getGrandTotalInr())
                        .balanceInr(s.getBalanceDueInr()).party(s.getVendorName()).build());
            }
            for (SupplierPayment p : f.supplierPayments()) {
                String kind = p.getDirection() == SupplierPaymentDirection.REFUND_RECEIVED ? "SUPPLIER_REFUND" : "SUPPLIER_PAYMENT";
                rows.add(MoneyRecord.builder().id(p.getId()).kind(kind).number(p.getVoucherNumber())
                        .date(p.getPaidOn()).amountInr(p.getAmountInr()).party(p.getVendorName())
                        .mode(p.getPaymentMode() != null ? p.getPaymentMode().name() : null)
                        .reversed(p.getReversedAt() != null || p.getReversesPaymentId() != null).parentId(p.getSupplierInvoiceId()).build());
            }
        }
        rows.sort(Comparator.comparing(MoneyRecord::getDate, Comparator.nullsLast(Comparator.<LocalDate>naturalOrder())));
        return rows;
    }

    private static Step step(String key, String state, String summary) {
        return Step.builder().key(key).state(state).summary(summary).build();
    }

    private static String money(BigDecimal v) {
        return com.voyra.crm.util.BookingNextStep.rupees(v);
    }

    private static BigDecimal nz(BigDecimal v) {
        return Objects.requireNonNullElse(v, BigDecimal.ZERO);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
