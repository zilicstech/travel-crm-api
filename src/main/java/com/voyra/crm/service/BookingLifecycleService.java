package com.voyra.crm.service;

import com.voyra.crm.dto.BookingInvoiceRequest;
import com.voyra.crm.dto.BookingLifecycleResponse;
import com.voyra.crm.dto.BookingLifecycleResponse.MoneyRecord;
import com.voyra.crm.dto.BookingLifecycleResponse.Step;
import com.voyra.crm.dto.BookingPaymentRequest;
import com.voyra.crm.dto.BookingSupplierPaymentRequest;
import com.voyra.crm.dto.InvoiceDraftRequest;
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
 * The booking lifecycle from the client's booking-management flow: create (draft) -> voucher ->
 * invoice -> payment received -> supplier paid, and after a cancel, refund to the client and
 * refund from the supplier. Reads every step's state from the real accounting records, and runs
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
    static final String PAY_SUPPLIER = "PAY_SUPPLIER";
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
    private final SupplierInvoiceService supplierInvoiceService;
    private final SupplierPaymentService supplierPaymentService;

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
        Booking booking = bookingService.findAccessibleBooking(bookingId);
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
        Booking booking = bookingService.findAccessibleBooking(bookingId);
        if (booking.getBookingStatus() == BookingStatus.CANCELLED) {
            throw new IllegalStateException("This booking is cancelled - record refunds instead");
        }
        List<Invoice> issued = liveTaxInvoices(booking.getId()).stream()
                .filter(i -> i.getStatus() != InvoiceLifecycle.DRAFT).toList();

        PaymentReceiptRequest receipt = new PaymentReceiptRequest();
        receipt.setAmount(request.getAmount());
        receipt.setPaymentMode(request.getPaymentMode());
        receipt.setReceivedOn(request.getReceivedOn());
        receipt.setInstrumentRef(blankToNull(request.getReference()));
        if (issued.isEmpty()) {
            BigDecimal open = nz(booking.getSellingPrice()).subtract(nz(booking.getReceivedTotalInr()))
                    .subtract(paymentReceiptService.walletBalance(booking.getClientId()));
            if (request.getAmount().compareTo(open) > 0) {
                throw new IllegalArgumentException("More than the " + open + " still to collect on this booking");
            }
            receipt.setClientId(booking.getClientId());
            receipt.setNotes("Advance for " + BookingService.bookingRef(booking.getBookingNumber()));
        } else {
            Invoice open = issued.stream().filter(i -> nz(i.getBalanceDue()).signum() > 0).findFirst()
                    .orElseThrow(() -> new IllegalStateException("This booking's invoice is fully paid"));
            receipt.setInvoiceId(open.getId());
        }
        paymentReceiptService.record(receipt);
        return build(gather(reload(booking)), currentViewer());
    }

    /**
     * One-step supplier payment: picks (or drafts, from the booking's supplier and net cost) the
     * supplier bill, approves it if still a draft, then records the payment. A bill above the
     * booking's cost cap needs an owner's override - without one the whole call rolls back.
     */
    @Transactional
    public BookingLifecycleResponse paySupplier(String bookingId, BookingSupplierPaymentRequest request) {
        Booking booking = bookingService.findAccessibleBooking(bookingId);
        SupplierInvoice bill = resolveBill(booking, request);

        if (request.getSupplierInvoiceNumber() != null && !request.getSupplierInvoiceNumber().isBlank()
                && bill.getStatus() == SupplierInvoiceStatus.DRAFT) {
            bill.setSupplierInvoiceNumber(request.getSupplierInvoiceNumber().trim());
            supplierInvoiceRepository.save(bill);
        }
        if (bill.getStatus() == SupplierInvoiceStatus.DRAFT || bill.getStatus() == SupplierInvoiceStatus.PENDING_APPROVAL) {
            SupplierInvoiceResponse approved = supplierInvoiceService.approve(bill.getId(), blankToNull(request.getOverrideReason()));
            if (approved.getStatus() == SupplierInvoiceStatus.PENDING_APPROVAL) {
                throw new IllegalStateException("This bill is above the booking's cost - an owner must approve it with a reason first");
            }
        }

        SupplierPaymentRequest payment = new SupplierPaymentRequest();
        payment.setVendorId(bill.getVendorId());
        payment.setSupplierInvoiceId(bill.getId());
        payment.setAmount(request.getAmount());
        payment.setPaymentMode(request.getPaymentMode());
        payment.setPaidOn(request.getPaidOn());
        payment.setInstrumentRef(blankToNull(request.getReference()));
        supplierPaymentService.pay(payment);
        log.info("Booking supplier paid: bookingId={}, billId={}, amount={}", booking.getId(), bill.getId(), request.getAmount());
        return build(gather(reload(booking)), currentViewer());
    }

    private SupplierInvoice resolveBill(Booking booking, BookingSupplierPaymentRequest request) {
        if (request.getSupplierInvoiceId() != null && !request.getSupplierInvoiceId().isBlank()) {
            SupplierInvoice bill = supplierInvoiceRepository.findById(request.getSupplierInvoiceId())
                    .orElseThrow(() -> new IllegalArgumentException("Supplier bill not found"));
            if (!booking.getId().equals(bill.getBookingId())) {
                throw new IllegalArgumentException("This bill belongs to another booking");
            }
            return bill;
        }
        String vendorId = request.getVendorId() != null && !request.getVendorId().isBlank()
                ? request.getVendorId() : booking.getVendorId();
        List<SupplierInvoice> open = openBills(booking.getId()).stream()
                .filter(b -> vendorId == null || vendorId.equals(b.getVendorId())).toList();
        if (open.size() == 1) {
            return open.get(0);
        }
        if (open.size() > 1) {
            throw new IllegalArgumentException("This booking has several open supplier bills - choose which one to pay");
        }
        if (vendorId == null) {
            throw new IllegalArgumentException("Pick the supplier to pay");
        }
        if (booking.getVendorId() == null) {
            booking.setVendorId(vendorId);
            bookingRepository.save(booking);
        }
        List<SupplierInvoiceResponse> drafted = supplierInvoiceService.createAutoDraft(booking, vendorId);
        String draftedId = drafted.stream().filter(d -> vendorId.equals(d.getVendorId())).map(SupplierInvoiceResponse::getId)
                .findFirst().orElseThrow(() -> new IllegalArgumentException("No cost on this booking for that supplier"));
        return supplierInvoiceRepository.findById(draftedId).orElseThrow();
    }

    /** Bills that can still take a payment, after an approval if needed. */
    private List<SupplierInvoice> openBills(String bookingId) {
        return supplierInvoiceRepository.findByBookingIdAndStatusNot(bookingId, SupplierInvoiceStatus.CANCELLED).stream()
                .filter(b -> b.getStatus() != SupplierInvoiceStatus.PAID && nz(b.getBalanceDue()).signum() > 0)
                .toList();
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
        List<SupplierPayment> payments = bills.stream()
                .flatMap(b -> supplierPaymentRepository.findBySupplierInvoiceIdOrderByPaidOnAscCreatedAtAsc(b.getId()).stream())
                .toList();
        return new Facts(booking, bookingDocumentRepository.findByBookingIdOrderBySortOrderAsc(id).size(),
                invoiceRepository.findByBookingId(id), receipts, bills, payments,
                paymentReceiptService.walletBalance(booking.getClientId()));
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

        BigDecimal netCost = nz(b.getNetCost());
        BigDecimal billed = f.bills().stream().map(x -> nz(x.getGrandTotalInr())).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal billBalance = f.bills().stream().map(x -> nz(x.getBalanceDueInr())).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal supplierPaid = billed.subtract(billBalance);
        BigDecimal supplierToPay = billed.max(netCost).subtract(supplierPaid).max(BigDecimal.ZERO);

        BigDecimal refundDue = nz(b.getCustomerRefundDue());
        BigDecimal refunded = nz(b.getRefundedTotalInr());
        BigDecimal refundExpected = nz(b.getSupplierRefundExpected());
        BigDecimal refundReceived = nz(b.getSupplierRefundReceived());
        boolean clientRefundOpen = refunded.compareTo(refundDue) < 0;
        boolean supplierRefundOpen = refundReceived.compareTo(refundExpected) < 0;

        List<Step> steps = new ArrayList<>();
        String with = b.getSupplier() != null && !b.getSupplier().isBlank() ? " with " + b.getSupplier() : "";
        steps.add(step("CREATED", "DONE", status == BookingStatus.PENDING ? "Draft - waiting for supplier confirmation"
                : cancelled ? "Booked" + with : (status == BookingStatus.COMPLETED ? "Completed" : "Confirmed") + with));
        steps.add(step("VOUCHER", f.documentCount() > 0 ? "DONE" : cancelled ? "NA" : "OPEN",
                f.documentCount() > 0 ? f.documentCount() + " document" + (f.documentCount() == 1 ? "" : "s") + " uploaded" : "No voucher uploaded yet"));
        steps.add(step("INVOICE", invoiced ? "DONE" : cancelled ? "NA" : "OPEN",
                invoiced ? "Invoiced " + money(invoicedTotal) : liveInvoices.isEmpty() ? "Not invoiced yet" : "Draft invoice not issued yet"));
        steps.add(paymentStep(cancelled, invoiced, received, toCollect, f.clientWalletInr()));
        steps.add(supplierStep(viewer, cancelled, netCost, supplierPaid, supplierToPay));
        if (cancelled) {
            steps.add(step("CANCELLED", "DONE", "Cancelled"));
            steps.add(step("CLIENT_REFUND", refundDue.signum() == 0 ? "NA" : clientRefundOpen ? (refunded.signum() > 0 ? "PARTIAL" : "OPEN") : "DONE",
                    refundDue.signum() == 0 ? "Nothing to refund" : money(refunded) + " of " + money(refundDue) + " refunded"));
            steps.add(step("SUPPLIER_REFUND", refundExpected.signum() == 0 ? "NA" : supplierRefundOpen ? (refundReceived.signum() > 0 ? "PARTIAL" : "OPEN") : "DONE",
                    refundExpected.signum() == 0 ? "Nothing expected" : money(refundReceived) + " of " + money(refundExpected) + " received"
                            + (b.getRefundCheckedAt() != null ? " - cross-checked" : "")));
        }

        List<String> actions = new ArrayList<>();
        if (ops && status == BookingStatus.PENDING) actions.add(CONFIRM);
        if (ops && status == BookingStatus.CONFIRMED) actions.add(COMPLETE);
        if (ops && !cancelled) actions.add(UPLOAD_VOUCHER);
        if (ops && live) actions.add(CANCEL);
        if (accounts && !cancelled && !invoiced && nz(b.getSellingPrice()).signum() > 0) actions.add(GENERATE_INVOICE);
        if (accounts && !cancelled && toCollect.signum() > 0) actions.add(RECORD_PAYMENT);
        if (accounts && !cancelled && supplierToPay.signum() > 0) actions.add(PAY_SUPPLIER);
        if (accounts && cancelled && clientRefundOpen) actions.add(REFUND_CLIENT);
        if (accounts && cancelled && supplierRefundOpen) actions.add(SUPPLIER_REFUND);
        if (accounts && cancelled && !clientRefundOpen && !supplierRefundOpen && b.getRefundCheckedAt() == null) actions.add(CROSS_CHECK);

        return BookingLifecycleResponse.builder()
                .bookingId(b.getId())
                .steps(steps)
                .allowedActions(actions)
                .records(records(f, accounts))
                .toCollectInr(toCollect)
                .supplierToPayInr(accounts ? supplierToPay : null)
                .clientWalletInr(accounts ? f.clientWalletInr() : null)
                .build();
    }

    private static Step paymentStep(boolean cancelled, boolean invoiced, BigDecimal received, BigDecimal toCollect, BigDecimal wallet) {
        if (cancelled) {
            return step("PAYMENT", received.signum() > 0 ? "DONE" : "NA", money(received) + " received before cancelling");
        }
        if (toCollect.signum() == 0 && received.signum() > 0) {
            return step("PAYMENT", "DONE", "Fully paid - " + money(received));
        }
        String held = !invoiced && nz(wallet).signum() > 0 ? " · " + money(wallet) + " held on account, applied on invoice" : "";
        return step("PAYMENT", received.signum() > 0 ? "PARTIAL" : "OPEN",
                (received.signum() > 0 ? money(received) + " received, " : "") + money(toCollect) + " to collect" + held);
    }

    private static Step supplierStep(Viewer viewer, boolean cancelled, BigDecimal netCost, BigDecimal paid, BigDecimal toPay) {
        String state = netCost.signum() == 0 && paid.signum() == 0 ? "NA"
                : toPay.signum() == 0 ? "DONE" : paid.signum() > 0 ? "PARTIAL" : cancelled ? "NA" : "OPEN";
        if (viewer == Viewer.AGENT) {
            return step("SUPPLIER", state, switch (state) {
                case "DONE" -> "Supplier paid";
                case "PARTIAL" -> "Supplier part paid";
                case "NA" -> "Nothing to pay";
                default -> "Not paid yet";
            });
        }
        return step("SUPPLIER", state, state.equals("NA") ? "Nothing to pay"
                : money(paid) + " paid" + (toPay.signum() > 0 ? ", " + money(toPay) + " to pay" : ""));
    }

    private static List<MoneyRecord> records(Facts f, boolean withSupplier) {
        List<MoneyRecord> rows = new ArrayList<>();
        for (Invoice i : f.invoices()) {
            if (i.getDocumentType() != InvoiceDocumentType.TAX_INVOICE || i.getStatus() == InvoiceLifecycle.DRAFT) continue;
            rows.add(MoneyRecord.builder().id(i.getId()).kind("INVOICE").number(i.getInvoiceNumber()).status(i.getStatus().name())
                    .date(i.getInvoiceDate()).amountInr(i.getGrandTotalInr()).balanceInr(i.getBalanceDueInr()).build());
        }
        for (PaymentReceipt r : f.receipts()) {
            rows.add(MoneyRecord.builder().id(r.getId()).kind(r.getDirection() == ReceiptDirection.REFUND ? "REFUND" : "RECEIPT")
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
