package com.voyra.crm.service;

import com.voyra.crm.dto.BookingCancelPreviewResponse;
import com.voyra.crm.dto.BookingCancelRequest;
import com.voyra.crm.dto.BookingResponse;
import com.voyra.crm.dto.CreditNoteRefundRequest;
import com.voyra.crm.dto.CreditNoteRequest;
import com.voyra.crm.dto.CreditNoteResponse;
import com.voyra.crm.dto.RefundSettlementRequest;
import com.voyra.crm.dto.SupplierCreditNoteRefundRequest;
import com.voyra.crm.dto.SupplierCreditNoteRequest;
import com.voyra.crm.entity.Booking;
import com.voyra.crm.entity.CreditNote;
import com.voyra.crm.entity.Invoice;
import com.voyra.crm.entity.SupplierCreditNote;
import com.voyra.crm.entity.SupplierInvoice;
import com.voyra.crm.enums.BookingStatus;
import com.voyra.crm.enums.CreditNoteReason;
import com.voyra.crm.enums.CreditNoteStatus;
import com.voyra.crm.enums.InvoiceDocumentType;
import com.voyra.crm.enums.InvoiceLifecycle;
import com.voyra.crm.enums.PaymentMode;
import com.voyra.crm.enums.RefundState;
import com.voyra.crm.enums.SupplierCreditNoteReason;
import com.voyra.crm.enums.SupplierCreditNoteStatus;
import com.voyra.crm.enums.SupplierInvoiceStatus;
import com.voyra.crm.repository.BookingRepository;
import com.voyra.crm.repository.CreditNoteRepository;
import com.voyra.crm.repository.InvoiceRepository;
import com.voyra.crm.repository.SupplierCreditNoteRepository;
import com.voyra.crm.repository.SupplierInvoiceRepository;
import com.voyra.crm.util.CancellationMath;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Cancels a whole booking and settles its money. The agent states what the supplier and the
 * agency keep; everything else - credit note, supplier credit note, revenue, refund figures -
 * follows from the existing accounting services, so agents never touch accounting documents.
 * Accounts then settles both sides through {@link #payCustomer} and {@link #receiveFromSupplier}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BookingCancellationService {

    private static final List<InvoiceLifecycle> LIVE = List.of(InvoiceLifecycle.ISSUED, InvoiceLifecycle.PARTIALLY_PAID, InvoiceLifecycle.PAID);
    private static final List<SupplierInvoiceStatus> UNPOSTED = List.of(SupplierInvoiceStatus.DRAFT, SupplierInvoiceStatus.PENDING_APPROVAL);

    private final BookingService bookingService;
    private final BookingRepository bookingRepository;
    private final BookingAccountingSync bookingAccountingSync;
    private final InvoiceRepository invoiceRepository;
    private final CreditNoteRepository creditNoteRepository;
    private final CreditNoteService creditNoteService;
    private final SupplierInvoiceRepository supplierInvoiceRepository;
    private final SupplierInvoiceService supplierInvoiceService;
    private final SupplierCreditNoteRepository supplierCreditNoteRepository;
    private final SupplierCreditNoteService supplierCreditNoteService;
    private final PaymentReceiptService paymentReceiptService;
    private final SupplierPaymentService supplierPaymentService;
    private final AuthorResolver authorResolver;
    private final RevenueRecognitionTenantService revenueRecognitionTenantService;

    @Transactional(readOnly = true)
    public BookingCancelPreviewResponse preview(String bookingId, BigDecimal supplierCharge, BigDecimal agencyFee) {
        assertNotNegative(supplierCharge, agencyFee);
        Booking booking = cancellable(bookingId);
        BigDecimal charge = nz(supplierCharge);
        BigDecimal kept = charge.add(nz(agencyFee));
        Invoice invoice = liveTaxInvoice(bookingId);
        BigDecimal customerPaid = customerPaid(booking);
        BigDecimal due = invoice != null
                ? creditNoteService.previewRefundable(invoice, kept)
                : CancellationMath.customerRefundDue(customerPaid, charge, nz(agencyFee));
        List<SupplierInvoice> bills = supplierInvoiceRepository.findByBookingIdAndStatusNot(bookingId, SupplierInvoiceStatus.CANCELLED);
        BigDecimal paidToSupplier = bills.stream().map(SupplierInvoice::getAmountPaid).reduce(BigDecimal.ZERO, BigDecimal::add);
        return BookingCancelPreviewResponse.builder()
                .customerPaid(customerPaid)
                .paidToSupplier(paidToSupplier)
                .customerRefundDue(due)
                .supplierRefundExpected(supplierExpected(booking, bills, charge, false))
                .invoiced(invoice != null)
                .build();
    }

    @Transactional
    public BookingResponse cancel(String bookingId, BookingCancelRequest request) {
        assertNotNegative(request.getSupplierCharge(), request.getAgencyFee());
        Booking booking = cancellable(bookingId);
        BigDecimal charge = nz(request.getSupplierCharge());
        BigDecimal fee = nz(request.getAgencyFee());
        bookingAccountingSync.syncPayment(bookingId);
        BigDecimal customerPaid = customerPaid(booking);

        String reason = request.getReason() == null || request.getReason().isBlank()
                ? request.getType().label() : request.getReason().trim();
        bookingService.markCancelled(booking, reason);

        Invoice invoice = liveTaxInvoice(bookingId);
        BigDecimal due;
        if (invoice != null) {
            CreditNoteRequest note = new CreditNoteRequest();
            note.setInvoiceId(invoice.getId());
            note.setReason(CreditNoteReason.BOOKING_CANCELLED);
            note.setReasonNote(reason);
            note.setCancellationFee(charge.add(fee).min(invoice.getTaxableValue()));
            CreditNoteResponse issued = creditNoteService.issue(creditNoteService.create(note).getId());
            due = issued.getRefundableAmount();
            revenueRecognitionTenantService.recognizeRetainedOnCancellation(invoice, issued.getTaxableValue(), LocalDate.now());
        } else {
            due = CancellationMath.customerRefundDue(customerPaid, charge, fee);
        }

        List<SupplierInvoice> bills = supplierInvoiceRepository.findByBookingIdAndStatusNot(bookingId, SupplierInvoiceStatus.CANCELLED);
        BigDecimal expected = supplierExpected(booking, bills, charge, true);

        booking.setCancellationType(request.getType());
        booking.setCancellationSupplierCharge(charge);
        booking.setCancellationAgencyFee(fee);
        booking.setCustomerRefundDue(due);
        booking.setSupplierRefundExpected(expected);
        booking.setRefundState(customerRefundState(due, booking.getRefundedTotalInr()));
        bookingRepository.save(booking);
        log.info("Booking cancelled: bookingId={}, customerRefundDue={}, supplierRefundExpected={}", bookingId, due, expected);
        return bookingService.toResponsePublic(booking);
    }

    @Transactional
    public BookingResponse payCustomer(String bookingId, RefundSettlementRequest request) {
        Booking booking = cancelled(bookingId);
        boolean toAccount = toAccount(request);
        BigDecimal outstanding = booking.getCustomerRefundDue().subtract(booking.getRefundedTotalInr());
        assertWithin(request.getAmount(), outstanding, "customer");
        CreditNote note = creditNoteRepository.findByBookingIdAndStatusNot(bookingId, CreditNoteStatus.CANCELLED).stream()
                .filter(n -> n.getStatus() == CreditNoteStatus.ISSUED).findFirst().orElse(null);
        if (note != null) {
            CreditNoteRefundRequest refund = new CreditNoteRefundRequest();
            refund.setAmount(request.getAmount());
            refund.setPaymentMode(request.getPaymentMode());
            refund.setInstrumentRef(request.getReference());
            refund.setReceivedOn(request.getPaidOn());
            creditNoteService.refund(note.getId(), refund, toAccount);
        } else {
            String proformaId = invoiceRepository.findByBookingId(bookingId).stream()
                    .filter(i -> i.getDocumentType() == InvoiceDocumentType.PROFORMA && i.getStatus() != InvoiceLifecycle.CANCELLED)
                    .map(Invoice::getId).findFirst().orElse(null);
            paymentReceiptService.refundAdvance(booking, proformaId, request, toAccount);
        }
        if (toAccount) {
            paymentReceiptService.creditWallet(booking.getClientId(), request.getAmount(), request.getPaidOn(),
                    "Refund on cancelled booking " + label(booking) + " kept on account");
        }
        Booking fresh = bookingRepository.findById(bookingId).orElseThrow();
        fresh.setRefundState(customerRefundState(fresh.getCustomerRefundDue(), fresh.getRefundedTotalInr()));
        bookingRepository.save(fresh);
        return bookingService.toResponsePublic(fresh);
    }

    @Transactional
    public BookingResponse receiveFromSupplier(String bookingId, RefundSettlementRequest request) {
        Booking booking = cancelled(bookingId);
        boolean toAccount = toAccount(request);
        BigDecimal outstanding = booking.getSupplierRefundExpected().subtract(booking.getSupplierRefundReceived());
        assertWithin(request.getAmount(), outstanding, "supplier");
        BigDecimal left = request.getAmount();
        List<SupplierCreditNote> notes = supplierCreditNoteRepository.findByBookingIdAndStatus(bookingId, SupplierCreditNoteStatus.RECORDED);
        BigDecimal open = notes.stream().map(n -> n.getRefundableAmount().subtract(n.getRefundedAmount()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (left.compareTo(open) > 0) {
            // Money with no credit note to post against would never reach the books.
            throw new IllegalStateException("No supplier credit note covers this refund - record the supplier bill first");
        }
        for (SupplierCreditNote note : notes) {
            BigDecimal noteOpen = note.getRefundableAmount().subtract(note.getRefundedAmount());
            if (left.signum() <= 0 || noteOpen.signum() <= 0) {
                continue;
            }
            SupplierCreditNoteRefundRequest refund = new SupplierCreditNoteRefundRequest();
            refund.setAmount(left.min(noteOpen));
            refund.setPaymentMode(request.getPaymentMode());
            refund.setInstrumentRef(request.getReference());
            refund.setReceivedOn(request.getPaidOn());
            supplierCreditNoteService.receiveRefund(note.getId(), refund, toAccount);
            if (toAccount) {
                supplierPaymentService.creditWallet(note.getVendorId(), refund.getAmount(), request.getPaidOn(),
                        "Refund on cancelled booking " + label(booking) + " kept in supplier wallet");
            }
            left = left.subtract(refund.getAmount());
        }
        booking.setSupplierRefundReceived(booking.getSupplierRefundReceived().add(request.getAmount()));
        bookingRepository.save(booking);
        return bookingService.toResponsePublic(booking);
    }

    /** Cancelled bookings where either side still has money to move, or that Accounts has not cross-checked yet. */
    @Transactional(readOnly = true)
    public List<BookingResponse> listOutstanding() {
        return bookingRepository.findByBookingStatus(BookingStatus.CANCELLED).stream()
                .filter(b -> b.getRefundCheckedAt() == null || !settled(b))
                .map(bookingService::toResponsePublic)
                .toList();
    }

    /** The accountant's cross-check ("cross checked by") once nothing is left to move on either side. */
    @Transactional
    public BookingResponse markChecked(String bookingId) {
        Booking booking = cancelled(bookingId);
        if (!settled(booking)) {
            throw new IllegalStateException("Settle both the customer and the supplier refund before cross-checking");
        }
        if (booking.getRefundCheckedAt() != null) {
            throw new IllegalStateException("Already cross-checked by " + booking.getRefundCheckedBy());
        }
        booking.setRefundCheckedAt(LocalDateTime.now());
        booking.setRefundCheckedBy(authorResolver.resolveCurrentAuthor().name());
        bookingRepository.save(booking);
        log.info("Cancelled booking cross-checked: bookingId={}", bookingId);
        return bookingService.toResponsePublic(booking);
    }

    // ---------------------------------------------------------------- internals

    /**
     * Supplier side. Approved bills get a supplier credit note down to the supplier's charge
     * (its refundable part is cash already paid above the charge); bills never approved carry no
     * postings and are simply cancelled. With no bill at all nothing is recorded as paid to the
     * supplier, so nothing is expected back - a refund needs a bill (and its credit note) to post
     * against. {@code write} false is the preview: same numbers, nothing recorded.
     */
    private BigDecimal supplierExpected(Booking booking, List<SupplierInvoice> bills, BigDecimal charge, boolean write) {
        if (bills.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal remainingCharge = charge;
        BigDecimal expected = BigDecimal.ZERO;
        for (SupplierInvoice bill : bills) {
            if (UNPOSTED.contains(bill.getStatus())) {
                if (write) {
                    supplierInvoiceService.cancelForBooking(bill.getId(), "Booking cancelled");
                }
                continue;
            }
            BigDecimal open = bill.getGrandTotal().subtract(bill.getCreditNoteTotal());
            BigDecimal kept = remainingCharge.min(open);
            remainingCharge = remainingCharge.subtract(kept);
            BigDecimal credit = open.subtract(kept);
            if (credit.signum() <= 0) {
                continue;
            }
            BigDecimal stillOwed = bill.getGrandTotal().subtract(bill.getAmountPaid()).subtract(bill.getCreditNoteTotal()).max(BigDecimal.ZERO);
            expected = expected.add(credit.subtract(stillOwed).max(BigDecimal.ZERO));
            if (write) {
                supplierCreditNoteService.record(supplierCreditNote(bill, credit));
            }
        }
        return expected;
    }

    /** GST on the credit in the same proportion as on the bill. */
    private static SupplierCreditNoteRequest supplierCreditNote(SupplierInvoice bill, BigDecimal credit) {
        BigDecimal ratio = bill.getGrandTotal().signum() == 0 ? BigDecimal.ZERO
                : credit.divide(bill.getGrandTotal(), 10, RoundingMode.HALF_UP);
        BigDecimal cgst = bill.getCgstAmount().multiply(ratio).setScale(2, RoundingMode.HALF_UP);
        BigDecimal sgst = bill.getSgstAmount().multiply(ratio).setScale(2, RoundingMode.HALF_UP);
        BigDecimal igst = bill.getIgstAmount().multiply(ratio).setScale(2, RoundingMode.HALF_UP);
        SupplierCreditNoteRequest r = new SupplierCreditNoteRequest();
        r.setSupplierInvoiceId(bill.getId());
        r.setReason(SupplierCreditNoteReason.BOOKING_CANCELLED);
        r.setReasonNote("Booking cancelled");
        r.setTaxableValue(credit.subtract(cgst).subtract(sgst).subtract(igst));
        r.setCgstAmount(cgst);
        r.setSgstAmount(sgst);
        r.setIgstAmount(igst);
        r.setRetentionFee(BigDecimal.ZERO);
        r.setNoteDate(LocalDate.now());
        return r;
    }

    private Booking cancellable(String bookingId) {
        Booking booking = bookingService.findAccessibleBooking(bookingId);
        if (booking.getBookingStatus() != BookingStatus.PENDING && booking.getBookingStatus() != BookingStatus.CONFIRMED) {
            throw new IllegalStateException("Only a pending or confirmed booking can be cancelled");
        }
        return booking;
    }

    private Booking cancelled(String bookingId) {
        Booking booking = bookingService.findAccessibleBooking(bookingId);
        if (booking.getBookingStatus() != BookingStatus.CANCELLED) {
            throw new IllegalStateException("This booking is not cancelled");
        }
        return booking;
    }

    private Invoice liveTaxInvoice(String bookingId) {
        return invoiceRepository.findByBookingIdAndDocumentTypeAndStatusNotOrderByCreatedAtAsc(
                        bookingId, InvoiceDocumentType.TAX_INVOICE, InvoiceLifecycle.CANCELLED).stream()
                .filter(i -> LIVE.contains(i.getStatus()))
                // An invoice someone already credited by hand gets no second credit note - the
                // refund then follows the money actually held, like an uninvoiced booking.
                .filter(i -> i.getCreditNoteTotal() == null || i.getCreditNoteTotal().signum() == 0)
                .findFirst().orElse(null);
    }

    private static BigDecimal customerPaid(Booking booking) {
        return nz(booking.getReceivedTotalInr()).subtract(nz(booking.getRefundedTotalInr())).max(BigDecimal.ZERO);
    }

    static RefundState customerRefundState(BigDecimal due, BigDecimal refunded) {
        if (nz(due).signum() <= 0) {
            return RefundState.NOT_APPLICABLE;
        }
        if (nz(refunded).signum() <= 0) {
            return RefundState.REFUND_PENDING;
        }
        return nz(refunded).compareTo(due) >= 0 ? RefundState.REFUNDED : RefundState.PARTIALLY_REFUNDED;
    }

    private static void assertNotNegative(BigDecimal... amounts) {
        for (BigDecimal a : amounts) {
            if (a != null && a.signum() < 0) {
                throw new IllegalArgumentException("Charges cannot be negative");
            }
        }
    }

    private static boolean settled(Booking b) {
        return b.getCustomerRefundDue().compareTo(b.getRefundedTotalInr()) <= 0
                && b.getSupplierRefundExpected().compareTo(b.getSupplierRefundReceived()) <= 0;
    }

    /** To account moves no cash, so it carries the ADJUSTMENT mode; otherwise a real mode is required. */
    private static boolean toAccount(RefundSettlementRequest request) {
        if (Boolean.TRUE.equals(request.getToAccount())) {
            request.setPaymentMode(PaymentMode.ADJUSTMENT);
            return true;
        }
        if (request.getPaymentMode() == null) {
            throw new IllegalArgumentException("Payment mode is required");
        }
        return false;
    }

    private static String label(Booking b) {
        return (b.getDestination() != null ? b.getDestination() + " " : "") + "(" + b.getId() + ")";
    }

    private static void assertWithin(BigDecimal amount, BigDecimal outstanding, String side) {
        if (amount.compareTo(outstanding) > 0) {
            throw new IllegalArgumentException("Amount is more than the " + outstanding.max(BigDecimal.ZERO)
                    + " still outstanding on the " + side + " side");
        }
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
