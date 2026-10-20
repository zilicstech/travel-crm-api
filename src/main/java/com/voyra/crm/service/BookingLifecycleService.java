package com.voyra.crm.service;

import com.voyra.crm.dto.BookingLifecycleResponse;
import com.voyra.crm.dto.BookingLifecycleResponse.MoneyRecord;
import com.voyra.crm.dto.BookingLifecycleResponse.Step;
import com.voyra.crm.dto.BookingPaymentRequest;
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
import java.util.Map;
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

    static final String UPLOAD_VOUCHER = "UPLOAD_VOUCHER";
    static final String CANCEL = "CANCEL";
    // Accounts generate the invoice from a draft (InvoiceDocumentService#createDrafts), or upload one
    // raised in the agency's own software.
    static final String GENERATE_INVOICE = "GENERATE_INVOICE";
    static final String RECORD_PAYMENT = "RECORD_PAYMENT";
    static final String REFUND_CLIENT = "REFUND_CLIENT";
    static final String SUPPLIER_REFUND = "SUPPLIER_REFUND";
    static final String CONFIRM_SUPPLIER_REFUND = "CONFIRM_SUPPLIER_REFUND";
    static final String REJECT_SUPPLIER_REFUND = "REJECT_SUPPLIER_REFUND";
    static final String CROSS_CHECK = "CROSS_CHECK";

    enum Viewer { OWNER, AGENT, ACCOUNTANT }

    /** Everything the steps are computed from, gathered once so the computation itself stays pure. */
    record Facts(Booking booking, int documentCount, List<Invoice> invoices, List<PaymentReceipt> receipts,
                 List<SupplierInvoice> bills, List<SupplierPayment> supplierPayments, BigDecimal clientWalletInr,
                 BigDecimal invoicedPriceInr, List<com.voyra.crm.entity.CreditNote> creditNotes,
                 Map<String, BigDecimal> ownOwedInr) {
        Facts(Booking booking, int documentCount, List<Invoice> invoices, List<PaymentReceipt> receipts,
              List<SupplierInvoice> bills, List<SupplierPayment> supplierPayments, BigDecimal clientWalletInr,
              BigDecimal invoicedPriceInr) {
            this(booking, documentCount, invoices, receipts, bills, supplierPayments, clientWalletInr, invoicedPriceInr, List.of());
        }

        Facts(Booking booking, int documentCount, List<Invoice> invoices, List<PaymentReceipt> receipts,
              List<SupplierInvoice> bills, List<SupplierPayment> supplierPayments, BigDecimal clientWalletInr,
              BigDecimal invoicedPriceInr, List<com.voyra.crm.entity.CreditNote> creditNotes) {
            this(booking, documentCount, invoices, receipts, bills, supplierPayments, clientWalletInr, invoicedPriceInr, creditNotes, Map.of());
        }

        /** What this booking still owes on an invoice that also bills other bookings (its own account); null when it bills only this one. */
        BigDecimal ownOwed(Invoice invoice) {
            return ownOwedInr.get(invoice.getId());
        }
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
    private final com.voyra.crm.repository.CreditNoteRepository creditNoteRepository;
    private final BookingAccountingSync bookingAccountingSync;
    private final BaseCurrency baseCurrency;

    @Transactional(readOnly = true)
    public BookingLifecycleResponse getLifecycle(String bookingId) {
        Booking booking = bookingService.findAccessibleBooking(bookingId);
        return build(gather(booking), currentViewer(), baseCurrency.code());
    }

    /**
     * Records money received from the client. With an invoice that still has a balance, the
     * receipt settles it; before any invoice exists, the money is held on the client's account
     * (the customer wallet) and is applied automatically when the invoice is generated.
     */
    @Transactional
    public BookingLifecycleResponse recordPayment(String bookingId, BookingPaymentRequest request) {
        Booking booking = lockAccessible(bookingId);
        List<Invoice> issued = liveTaxInvoices(booking.getId()).stream()
                .filter(i -> i.getStatus() != InvoiceLifecycle.DRAFT).toList();
        // On an invoice that also bills other bookings, a payment for this one settles only its own account, even
        // while the invoice reads paid because another booking's refund is unpaid (the receipt caps it). The
        // payment is in base; an invoice billed in another currency owes its balance at its own rate.
        Map<String, BigDecimal> owing = new java.util.HashMap<>();
        issued.forEach(i -> owing.put(i.getId(), toBase(i, invoiceDocumentService.weightOf(i, booking.getId()).compareTo(BigDecimal.ONE) == 0
                ? nz(i.getBalanceDue()).max(BigDecimal.ZERO)
                : bookingAccountingSync.positionOn(i, booking.getId(), null).owed().max(BigDecimal.ZERO))));
        if (booking.getBookingStatus() == BookingStatus.CANCELLED) {
            // Only what its invoices still owe (cancellation charges); nothing is held for a cancelled trip.
            BigDecimal owed = owing.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            if (owed.signum() == 0) {
                throw new IllegalStateException("This booking is cancelled - record refunds instead");
            }
            if (request.getAmount().compareTo(owed) > 0) {
                throw new IllegalArgumentException("More than the " + owed + " still owed on this cancelled booking");
            }
        }

        // Never capped by an estimate: the client may pay the GST-inclusive total before the invoice
        // exists, or for a voucher added after it. Open invoices are settled first, oldest first; the
        // rest is held on the client's account for this booking and applied to its next invoice.
        BigDecimal left = request.getAmount();
        for (Invoice invoice : issued.stream().filter(i -> owing.get(i.getId()).signum() > 0).toList()) {
            if (left.signum() == 0) break;
            BigDecimal part = left.min(owing.get(invoice.getId()));
            PaymentReceiptRequest r = receiptRequest(request, inInvoiceCurrency(invoice, part));
            r.setInvoiceId(invoice.getId());
            r.setBookingId(booking.getId());
            paymentReceiptService.record(r);
            left = left.subtract(part);
        }
        if (left.signum() > 0) {
            PaymentReceiptRequest receipt = receiptRequest(request, left);
            receipt.setClientId(booking.getClientId());
            receipt.setBookingId(booking.getId());
            receipt.setNotes("Advance for " + BookingService.bookingRef(booking.getBookingNumber()));
            paymentReceiptService.record(receipt);
        }
        return build(gather(reload(booking)), currentViewer(), baseCurrency.code());
    }

    private static BigDecimal toBase(Invoice invoice, BigDecimal amount) {
        BigDecimal fx = invoice.getFxRateToInr();
        return fx == null || fx.compareTo(BigDecimal.ONE) == 0 ? amount : amount.multiply(fx).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    /** A base amount on an invoice billed in another currency; a part settling the whole balance takes it exactly. */
    private BigDecimal inInvoiceCurrency(Invoice invoice, BigDecimal baseAmount) {
        BigDecimal fx = invoice.getFxRateToInr();
        if (fx == null || fx.compareTo(BigDecimal.ONE) == 0) return baseAmount;
        BigDecimal balance = nz(invoice.getBalanceDue());
        return baseAmount.compareTo(toBase(invoice, balance)) >= 0 ? balance : baseAmount.divide(fx, 2, java.math.RoundingMode.HALF_UP).min(balance);
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

    private Booking reload(Booking booking) {
        return bookingRepository.findById(booking.getId()).orElse(booking);
    }

    Facts gather(Booking booking) {
        String id = booking.getId();
        List<PaymentReceipt> receipts = new ArrayList<>(paymentReceiptRepository.findByBookingIdAndDirection(id, ReceiptDirection.RECEIPT));
        receipts.addAll(paymentReceiptRepository.findByBookingIdAndDirection(id, ReceiptDirection.REFUND));
        List<SupplierInvoice> bills = supplierInvoiceRepository.findByBookingIdAndStatusNot(id, SupplierInvoiceStatus.CANCELLED);
        List<SupplierPayment> payments = supplierPaymentRepository.findByBookingId(id);
        List<Invoice> invoices = invoiceRepository.findByBookingId(id);
        Map<String, BigDecimal> ownOwed = new java.util.HashMap<>();
        invoices.stream().filter(i -> invoiceDocumentService.weightOf(i, id).compareTo(BigDecimal.ONE) != 0)
                .forEach(i -> ownOwed.put(i.getId(), toBase(i, bookingAccountingSync.positionOn(i, id, null).owed())));
        return new Facts(booking, bookingDocumentRepository.findByBookingIdOrderBySortOrderAsc(id).size(),
                invoices, receipts, bills, payments,
                paymentReceiptService.availableWalletFor(booking.getClientId(), booking.getId()),
                bookingService.invoicedPrice(id),
                creditNoteRepository.findByBookingIdAndStatusNot(id, com.voyra.crm.enums.CreditNoteStatus.CANCELLED).stream()
                        .filter(c -> c.getStatus() == com.voyra.crm.enums.CreditNoteStatus.ISSUED).toList(),
                ownOwed);
    }

    private static Viewer currentViewer() {
        CustomUserPrincipal principal = SecurityContextUtil.getCurrentUserOrThrow();
        return principal.isAgent() ? Viewer.AGENT : principal.isAccountant() ? Viewer.ACCOUNTANT : Viewer.OWNER;
    }

    /** Pure: the whole response from the gathered facts and who is asking. */
    static BookingLifecycleResponse build(Facts f, Viewer viewer) {
        return build(f, viewer, "INR");
    }

    static BookingLifecycleResponse build(Facts f, Viewer viewer, String currency) {
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
        // After the invoice, a voucher added since then is still to collect too (before its GST, which its own invoice adds).
        BigDecimal sinceInvoice = cancelled || !invoiced ? BigDecimal.ZERO
                : nz(b.getSellingPrice()).subtract(nz(f.invoicedPriceInr())).max(BigDecimal.ZERO);
        // What its invoices still owe for this booking - on an invoice shared with other bookings, its own account
        // (its share less its own credit notes and the payments recorded for it).
        BigDecimal owedOnInvoices = liveInvoices.stream().filter(i -> i.getStatus() != InvoiceLifecycle.DRAFT)
                .map(i -> (f.ownOwed(i) != null ? f.ownOwed(i) : nz(i.getBalanceDueInr())).max(BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean shared = liveInvoices.stream().anyMatch(i -> f.ownOwed(i) != null);
        // A cancelled booking still owes whatever its invoices leave unpaid (e.g. cancellation charges).
        BigDecimal toCollect = cancelled ? owedOnInvoices
                : (invoiced ? (shared ? owedOnInvoices : invoicedTotal.subtract(received)).add(sinceInvoice)
                        : nz(b.getSellingPrice()).subtract(received).subtract(nz(f.clientWalletInr())))
                        .max(BigDecimal.ZERO);

        BigDecimal refundDue = nz(b.getCustomerRefundDue());
        BigDecimal refunded = nz(b.getRefundedTotalInr());
        BigDecimal refundExpected = nz(b.getSupplierRefundExpected());
        BigDecimal refundReceived = nz(b.getSupplierRefundReceived());
        boolean clientRefundOpen = refunded.compareTo(refundDue) < 0;
        boolean supplierRefundOpen = refundReceived.compareTo(refundExpected) < 0;
        BigDecimal claim = b.getSupplierRefundClaimAmount();

        List<Step> steps = new ArrayList<>();
        // Who it is really booked with: the vouchers' own suppliers (their bills), else the booking's default.
        String suppliers = f.bills().stream().map(SupplierInvoice::getVendorName)
                .filter(n -> n != null && !n.isBlank()).distinct().collect(java.util.stream.Collectors.joining(", "));
        if (suppliers.isEmpty() && b.getSupplier() != null) suppliers = b.getSupplier().trim();
        String with = suppliers.isEmpty() ? "" : " with " + suppliers;
        steps.add(step("CREATED", "DONE", status == BookingStatus.PENDING ? "Pending - upload the voucher with its cost and markup to confirm"
                : cancelled ? "Booked" + with : (status == BookingStatus.COMPLETED ? "Completed" : "Confirmed") + with));
        // A voucher still without its cost and markup leaves the booking pending - the step isn't done yet.
        boolean unpriced = f.documentCount() > 0 && status == BookingStatus.PENDING;
        steps.add(step("VOUCHER", f.documentCount() > 0 ? (unpriced ? "PARTIAL" : "DONE") : cancelled ? "NA" : "OPEN",
                f.documentCount() > 0 ? f.documentCount() + " voucher" + (f.documentCount() == 1 ? "" : "s") + " uploaded"
                        + (unpriced ? " - add the cost and markup to confirm" : "") : "No voucher uploaded yet"));
        BigDecimal uninvoiced = cancelled ? BigDecimal.ZERO : nz(b.getSellingPrice()).subtract(nz(f.invoicedPriceInr())).max(BigDecimal.ZERO);
        steps.add(step("INVOICE", invoiced ? (uninvoiced.signum() > 0 ? "PARTIAL" : "DONE") : cancelled ? "NA" : "OPEN",
                invoiced ? "Invoiced " + money(invoicedTotal, currency) + (uninvoiced.signum() > 0 ? " · " + money(uninvoiced, currency) + " added since the last invoice" : "")
                        : liveInvoices.isEmpty() ? "Not invoiced yet" : "Draft invoice not issued yet"));
        steps.add(paymentStep(cancelled, invoiced, received, toCollect, f.clientWalletInr(), currency));
        if (cancelled) {
            steps.add(step("CANCELLED", "DONE", "Cancelled"));
            // The supplier's refund comes first, then the client's - the order the money moves in.
            String supplierRefundState = refundExpected.signum() == 0 ? "NA" : supplierRefundOpen ? (refundReceived.signum() > 0 ? "PARTIAL" : "OPEN") : "DONE";
            // The agent records this refund, so every role sees its amounts (never the bills or net cost behind them).
            String supplierRefundSummary = refundExpected.signum() == 0 ? "Nothing expected"
                    : money(refundReceived, currency) + " of " + money(refundExpected, currency) + " received"
                    + (claim != null ? " · " + money(claim, currency) + " claimed into bank - waiting for the accountant's check" : "");
            steps.add(step("SUPPLIER_REFUND", supplierRefundState,
                    supplierRefundSummary + (b.getRefundCheckedAt() != null ? " - cross-checked" : "")));
            steps.add(step("CLIENT_REFUND", refundDue.signum() == 0 ? "NA" : clientRefundOpen ? (refunded.signum() > 0 ? "PARTIAL" : "OPEN") : "DONE",
                    refundDue.signum() == 0 ? "Nothing to refund" : money(refunded, currency) + " of " + money(refundDue, currency) + " refunded"));
        }

        List<String> actions = new ArrayList<>();
        if (ops && !cancelled) actions.add(UPLOAD_VOUCHER);
        if (ops && live) actions.add(CANCEL);
        if (accounts && !cancelled && (invoiced ? uninvoiced.signum() > 0 : nz(b.getSellingPrice()).signum() > 0)) {
            if (status != BookingStatus.PENDING) actions.add(GENERATE_INVOICE);
        }
        // Only while something is still to collect: invoiced and unpaid, not invoiced yet (held as an advance until the
        // invoice), a voucher added since the last invoice, or a cancelled booking's charges. A fully paid booking has none.
        if (accounts && toCollect.signum() > 0) actions.add(RECORD_PAYMENT);
        if (accounts && cancelled && clientRefundOpen) actions.add(REFUND_CLIENT);
        if (ops && cancelled && supplierRefundOpen && claim == null) actions.add(SUPPLIER_REFUND);
        if (accounts && cancelled && claim != null) {
            actions.add(CONFIRM_SUPPLIER_REFUND);
            actions.add(REJECT_SUPPLIER_REFUND);
        }
        if (accounts && cancelled && !clientRefundOpen && !supplierRefundOpen && claim == null && toCollect.signum() == 0
                && b.getRefundCheckedAt() == null) actions.add(CROSS_CHECK);

        return BookingLifecycleResponse.builder()
                .bookingId(b.getId())
                .steps(steps)
                .allowedActions(actions)
                .records(records(f, accounts))
                .toCollectInr(toCollect)
                .uninvoicedInr(invoiced ? uninvoiced : cancelled ? BigDecimal.ZERO : nz(b.getSellingPrice()))
                .clientWalletInr(accounts ? f.clientWalletInr() : null)
                .supplierRefundOutstandingInr(cancelled ? refundExpected.subtract(refundReceived).max(BigDecimal.ZERO) : null)
                .supplierRefundClaim(claim == null ? null : BookingLifecycleResponse.SupplierRefundClaim.builder()
                        .amountInr(claim).receivedOn(b.getSupplierRefundClaimOn())
                        .reference(b.getSupplierRefundClaimReference()).claimedBy(b.getSupplierRefundClaimBy()).build())
                .build();
    }

    private static Step paymentStep(boolean cancelled, boolean invoiced, BigDecimal received, BigDecimal toCollect, BigDecimal wallet, String currency) {
        if (cancelled) {
            if (toCollect.signum() > 0) {
                return step("PAYMENT", "OPEN", money(toCollect, currency) + " cancellation charges to collect");
            }
            if (received.signum() == 0 && nz(wallet).signum() > 0) {
                return step("PAYMENT", "DONE", money(wallet, currency) + " held as an advance before cancelling");
            }
            // Not "before cancelling": cancellation charges are often paid after the cancel.
            return step("PAYMENT", received.signum() > 0 ? "DONE" : "NA", money(received, currency) + " received");
        }
        if (toCollect.signum() == 0 && received.signum() > 0) {
            return step("PAYMENT", "DONE", "Fully paid - " + money(received, currency));
        }
        String held = !invoiced && nz(wallet).signum() > 0 ? " · " + money(wallet, currency) + " held on account, applied on invoice" : "";
        return step("PAYMENT", received.signum() > 0 ? "PARTIAL" : "OPEN",
                (received.signum() > 0 ? money(received, currency) + " received, " : "") + money(toCollect, currency) + " to collect" + held);
    }

    private static List<MoneyRecord> records(Facts f, boolean withSupplier) {
        List<MoneyRecord> rows = new ArrayList<>();
        for (Invoice i : f.invoices()) {
            if (i.getDocumentType() != InvoiceDocumentType.TAX_INVOICE || i.getStatus() == InvoiceLifecycle.DRAFT) continue;
            rows.add(MoneyRecord.builder().id(i.getId()).kind("INVOICE").number(i.getInvoiceNumber()).status(i.getStatus().name())
                    .date(i.getInvoiceDate()).amountInr(i.getGrandTotalInr()).balanceInr(i.getBalanceDueInr())
                    .hasFile(i.getPdfFileKey() != null).build());
        }
        // Shown to every role like the invoices they reduce: why money is owed back after a cancel.
        for (com.voyra.crm.entity.CreditNote c : f.creditNotes()) {
            rows.add(MoneyRecord.builder().id(c.getId()).kind("CREDIT_NOTE").number(c.getCreditNoteNumber()).status(c.getStatus().name())
                    .date(c.getNoteDate()).amountInr(c.getTotalAmountInr()).parentId(c.getInvoiceId()).build());
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

    private static String money(BigDecimal v, String currency) {
        return com.voyra.crm.util.BookingNextStep.money(v, currency);
    }

    private static BigDecimal nz(BigDecimal v) {
        return Objects.requireNonNullElse(v, BigDecimal.ZERO);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
