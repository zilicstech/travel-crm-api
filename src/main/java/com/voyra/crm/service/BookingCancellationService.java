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
 * Accounts refunds the customer through {@link #payCustomer}; the agent records the supplier's
 * refund through {@link #recordSupplierRefund}, and Accounts confirms one that went to the bank.
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
    private final BookingInvoiceIssuer bookingInvoiceIssuer;

    @Transactional(readOnly = true)
    public BookingCancelPreviewResponse preview(String bookingId, BigDecimal airlineCharge, BigDecimal supplierCharge, BigDecimal agencyFee) {
        assertNotNegative(airlineCharge, supplierCharge, agencyFee);
        Booking booking = cancellable(bookingId);
        // The supplier side keeps the airline's charge and its own fee; both come out of its refund.
        BigDecimal charge = nz(airlineCharge).add(nz(supplierCharge));
        BigDecimal fee = nz(agencyFee);
        List<Invoice> invoices = liveTaxInvoices(bookingId);
        BigDecimal held = held(booking);
        BigDecimal customerPaid = customerPaid(booking).add(held);
        // Mirrors cancel(): a credit note on every invoice, or - before any invoice - the charges invoice (with its GST).
        BigDecimal chargesInvoice = invoices.isEmpty() && charge.add(fee).signum() > 0
                ? bookingInvoiceIssuer.cancellationChargesTotal(booking, charge, fee) : null;
        BigDecimal due = !invoices.isEmpty()
                ? spread(invoices, charge, fee).stream()
                        .map(c -> creditNoteService.previewRefundable(c.invoice(), c.cancellationFee(), c.supplierRetained()))
                        .reduce(held, BigDecimal::add)
                : chargesInvoice != null
                        ? customerPaid.subtract(chargesInvoice).max(BigDecimal.ZERO)
                        : CancellationMath.customerRefundDue(customerPaid, charge, fee);
        List<SupplierInvoice> bills = supplierInvoiceRepository.findByBookingIdAndStatusNot(bookingId, SupplierInvoiceStatus.CANCELLED);
        BigDecimal paidToSupplier = bills.stream().map(SupplierInvoice::getAmountPaid).reduce(BigDecimal.ZERO, BigDecimal::add);
        return BookingCancelPreviewResponse.builder()
                .customerPaid(customerPaid)
                .paidToSupplier(paidToSupplier)
                .customerRefundDue(due)
                .supplierRefundExpected(supplierExpected(booking, bills, charge, false))
                .supplierChargeOwed(supplierChargeOwed(bills, charge))
                .chargesInvoiceTotal(chargesInvoice)
                .invoiced(!invoices.isEmpty())
                .build();
    }

    @Transactional
    public BookingResponse cancel(String bookingId, BookingCancelRequest request) {
        assertNotNegative(request.getAirlineCharge(), request.getSupplierCharge(), request.getAgencyFee());
        lock(bookingId);
        Booking booking = cancellable(bookingId);
        if (request.getType() == com.voyra.crm.enums.CancellationType.VOID && booking.getType() != com.voyra.crm.enums.BookingType.FLIGHT) {
            throw new IllegalArgumentException("Void is only for airline tickets - use Cancellation or No show");
        }
        BigDecimal charge = nz(request.getAirlineCharge()).add(nz(request.getSupplierCharge()));
        BigDecimal fee = nz(request.getAgencyFee());
        bookingAccountingSync.syncPayment(bookingId);
        List<Invoice> invoices = liveTaxInvoices(bookingId);
        BigDecimal held = held(booking);
        BigDecimal customerPaid = customerPaid(booking).add(held);

        String reason = request.getReason() == null || request.getReason().isBlank()
                ? request.getType().label() : request.getReason().trim();
        // Cancelled before any invoice: what the supplier and the agency kept is billed on its own
        // invoice (issued while the booking is still live), and any advance held is applied to it.
        com.voyra.crm.dto.InvoiceResponse charges = invoices.isEmpty() && charge.add(fee).signum() > 0
                ? bookingInvoiceIssuer.issueCancellationCharges(booking, charge, fee) : null;
        bookingService.markCancelled(booking, reason);

        BigDecimal due;
        if (!invoices.isEmpty()) {
            // Every invoice gets its own credit note (its own GST split); money still held for the booking goes back too.
            due = held;
            for (Credit c : spread(invoices, charge, fee)) {
                CreditNoteRequest note = new CreditNoteRequest();
                note.setInvoiceId(c.invoice().getId());
                note.setReason(CreditNoteReason.BOOKING_CANCELLED);
                note.setReasonNote(reason);
                note.setCancellationFee(c.cancellationFee());
                note.setSupplierRetained(c.supplierRetained());
                CreditNoteResponse issued = creditNoteService.issue(creditNoteService.create(note).getId());
                due = due.add(nz(issued.getRefundableAmount()));
                revenueRecognitionTenantService.recognizeRetainedOnCancellation(c.invoice(), issued.getTaxableValue(), LocalDate.now());
            }
        } else if (charges != null) {
            // What the client paid less the charges invoice (GST included); a shortfall is collected on that invoice.
            due = customerPaid.subtract(nz(charges.getGrandTotal())).max(BigDecimal.ZERO);
            // The trip won't happen: what was kept is earned now, not deferred to the departure date.
            invoiceRepository.findById(charges.getId()).ifPresent(i ->
                    revenueRecognitionTenantService.recognizeRetainedOnCancellation(i, BigDecimal.ZERO, LocalDate.now()));
        } else {
            due = CancellationMath.customerRefundDue(customerPaid, charge, fee);
        }

        List<SupplierInvoice> bills = supplierInvoiceRepository.findByBookingIdAndStatusNot(bookingId, SupplierInvoiceStatus.CANCELLED);
        BigDecimal expected = supplierExpected(booking, bills, charge, true);

        booking.setCancellationType(request.getType());
        booking.setCancellationSupplierCharge(charge);
        booking.setCancellationAirlineCharge(nz(request.getAirlineCharge()));
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
        lock(bookingId);
        Booking booking = cancelled(bookingId);
        boolean toAccount = toAccount(request);
        BigDecimal outstanding = booking.getCustomerRefundDue().subtract(booking.getRefundedTotalInr());
        assertWithin(request.getAmount(), outstanding, "customer");
        // Out of each credit note's open refund in turn, then the rest out of the advance still held for the booking.
        BigDecimal left = request.getAmount();
        for (CreditNote note : creditNoteRepository.findByBookingIdAndStatusNot(bookingId, CreditNoteStatus.CANCELLED)) {
            BigDecimal open = nz(note.getRefundableAmount()).subtract(nz(note.getRefundedAmount()));
            if (note.getStatus() != CreditNoteStatus.ISSUED || left.signum() <= 0 || open.signum() <= 0) {
                continue;
            }
            CreditNoteRefundRequest refund = new CreditNoteRefundRequest();
            refund.setAmount(left.min(open));
            refund.setPaymentMode(request.getPaymentMode());
            refund.setInstrumentRef(request.getReference());
            refund.setReceivedOn(request.getPaidOn());
            creditNoteService.refund(note.getId(), refund, toAccount);
            left = left.subtract(refund.getAmount());
        }
        if (left.signum() > 0) {
            String proformaId = invoiceRepository.findByBookingId(bookingId).stream()
                    .filter(i -> i.getDocumentType() == InvoiceDocumentType.PROFORMA && i.getStatus() != InvoiceLifecycle.CANCELLED)
                    .map(Invoice::getId).findFirst().orElse(null);
            RefundSettlementRequest rest = new RefundSettlementRequest();
            rest.setAmount(left);
            rest.setPaidOn(request.getPaidOn());
            rest.setPaymentMode(request.getPaymentMode());
            rest.setReference(request.getReference());
            rest.setToAccount(request.getToAccount());
            paymentReceiptService.refundAdvance(booking, proformaId, rest, toAccount);
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

    /**
     * The agent records the supplier's refund. Kept in the supplier's wallet (e.g. a TripJack
     * balance the agent can see in the supplier portal) it is posted now. Into the agency's bank it
     * is only a claim: nothing reaches the books until the accountant finds it on the bank
     * statement and confirms it - see {@link #confirmSupplierRefund}.
     */
    @Transactional
    public BookingResponse recordSupplierRefund(String bookingId, RefundSettlementRequest request) {
        lock(bookingId);
        Booking booking = cancelled(bookingId);
        // Either way, an open bank claim is settled first - otherwise the claim could never be confirmed.
        if (booking.getSupplierRefundClaimAmount() != null) {
            throw new IllegalArgumentException("A bank refund is already waiting for the accountant's check");
        }
        if (Boolean.TRUE.equals(request.getToAccount())) {
            postSupplierRefund(booking, request, toAccount(request));
            return bookingService.toResponsePublic(booking);
        }
        assertWithin(request.getAmount(), supplierOutstanding(booking), "supplier");
        assertCoveredByCreditNotes(bookingId, request.getAmount());
        booking.setSupplierRefundClaimAmount(request.getAmount());
        booking.setSupplierRefundClaimOn(request.getPaidOn());
        booking.setSupplierRefundClaimReference(request.getReference() == null || request.getReference().isBlank()
                ? null : request.getReference().trim());
        booking.setSupplierRefundClaimBy(authorResolver.resolveCurrentAuthor().name());
        booking.setSupplierRefundClaimAt(LocalDateTime.now());
        bookingRepository.save(booking);
        log.info("Supplier refund claimed into bank: bookingId={}, amount={}", bookingId, request.getAmount());
        return bookingService.toResponsePublic(booking);
    }

    /** The accountant found the claimed supplier refund in the bank: post it (up to the claim) and close the claim. */
    @Transactional
    public BookingResponse confirmSupplierRefund(String bookingId, RefundSettlementRequest request) {
        lock(bookingId);
        Booking booking = cancelled(bookingId);
        BigDecimal claim = booking.getSupplierRefundClaimAmount();
        if (claim == null) {
            throw new IllegalStateException("No supplier refund is waiting for a bank check");
        }
        if (request.getAmount().compareTo(claim) > 0) {
            throw new IllegalArgumentException("Amount is more than the " + claim + " the agent recorded");
        }
        request.setToAccount(false);
        postSupplierRefund(booking, request, toAccount(request));
        clearClaim(booking);
        bookingRepository.save(booking);
        return bookingService.toResponsePublic(booking);
    }

    /** The claimed refund is not in the bank - the claim is dropped and the agent records it again. */
    @Transactional
    public BookingResponse rejectSupplierRefund(String bookingId) {
        lock(bookingId);
        Booking booking = cancelled(bookingId);
        if (booking.getSupplierRefundClaimAmount() == null) {
            throw new IllegalStateException("No supplier refund is waiting for a bank check");
        }
        log.info("Supplier refund claim rejected: bookingId={}, amount={}, claimedBy={}",
                bookingId, booking.getSupplierRefundClaimAmount(), booking.getSupplierRefundClaimBy());
        clearClaim(booking);
        bookingRepository.save(booking);
        return bookingService.toResponsePublic(booking);
    }

    private void postSupplierRefund(Booking booking, RefundSettlementRequest request, boolean toAccount) {
        String bookingId = booking.getId();
        assertWithin(request.getAmount(), supplierOutstanding(booking), "supplier");
        assertCoveredByCreditNotes(bookingId, request.getAmount());
        BigDecimal left = request.getAmount();
        for (SupplierCreditNote note : supplierCreditNoteRepository.findByBookingIdAndStatus(bookingId, SupplierCreditNoteStatus.RECORDED)) {
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
    }

    /** Money with no credit note to post against would never reach the books. */
    private void assertCoveredByCreditNotes(String bookingId, BigDecimal amount) {
        BigDecimal open = supplierCreditNoteRepository.findByBookingIdAndStatus(bookingId, SupplierCreditNoteStatus.RECORDED).stream()
                .map(n -> n.getRefundableAmount().subtract(n.getRefundedAmount()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (amount.compareTo(open) > 0) {
            throw new IllegalStateException("No supplier credit note covers this refund - record the supplier bill first");
        }
    }

    private static BigDecimal supplierOutstanding(Booking booking) {
        return booking.getSupplierRefundExpected().subtract(booking.getSupplierRefundReceived());
    }

    private static void clearClaim(Booking booking) {
        booking.setSupplierRefundClaimAmount(null);
        booking.setSupplierRefundClaimOn(null);
        booking.setSupplierRefundClaimReference(null);
        booking.setSupplierRefundClaimBy(null);
        booking.setSupplierRefundClaimAt(null);
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
        lock(bookingId);
        Booking booking = cancelled(bookingId);
        if (!settled(booking) || booking.getSupplierRefundClaimAmount() != null) {
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
        // The charge comes out of money already with the supplier first; only what is left of it becomes a payable on an unpaid draft.
        List<SupplierInvoice> ordered = bills.stream()
                .sorted(java.util.Comparator.comparing((SupplierInvoice b) -> UNPOSTED.contains(b.getStatus())))
                .toList();
        for (SupplierInvoice bill : ordered) {
            if (UNPOSTED.contains(bill.getStatus())) {
                // An unpaid draft bill with a charge still to place becomes a real payable for that charge:
                // the supplier kept it, so the agency owes it even though nothing was paid yet. With no charge
                // left, the draft is simply withdrawn as before.
                if (remainingCharge.signum() == 0) {
                    if (write) {
                        supplierInvoiceService.cancelForBooking(bill.getId(), "Booking cancelled");
                    }
                    continue;
                }
                if (write) {
                    var approved = supplierInvoiceService.approveForBooking(bill.getId(), "Supplier cancellation charge on a cancelled booking");
                    if (approved.getStatus() != SupplierInvoiceStatus.APPROVED) {
                        continue;
                    }
                    bill = supplierInvoiceRepository.findById(bill.getId()).orElse(bill);
                }
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

    /**
     * Row lock on the booking before reading what is owed: a second concurrent refund (or a
     * double cancel) waits here, then reads the first one's result and is refused as over the
     * amount still outstanding instead of posting money twice.
     */
    private void lock(String bookingId) {
        bookingRepository.findByIdForUpdate(bookingId);
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

    private List<Invoice> liveTaxInvoices(String bookingId) {
        return invoiceRepository.findByBookingIdAndDocumentTypeAndStatusNotOrderByCreatedAtAsc(
                        bookingId, InvoiceDocumentType.TAX_INVOICE, InvoiceLifecycle.CANCELLED).stream()
                .filter(i -> LIVE.contains(i.getStatus()))
                // An invoice someone already credited by hand gets no second credit note - the
                // refund then follows the money actually held, like an uninvoiced booking.
                .filter(i -> i.getCreditNoteTotal() == null || i.getCreditNoteTotal().signum() == 0)
                .toList();
    }

    /** One invoice's share of what is kept: {@code cancellationFee} is all of it, {@code supplierRetained} the supplier's part. */
    private record Credit(Invoice invoice, BigDecimal cancellationFee, BigDecimal supplierRetained) {
    }

    /**
     * Spreads what is kept over the invoices oldest first, each up to its taxable value: the
     * supplier's charge first, then the agency fee. One invoice gets everything it can hold, as before.
     */
    private static List<Credit> spread(List<Invoice> invoices, BigDecimal charge, BigDecimal fee) {
        BigDecimal chargeLeft = charge;
        BigDecimal feeLeft = fee;
        List<Credit> credits = new java.util.ArrayList<>();
        for (Invoice invoice : invoices) {
            BigDecimal retained = chargeLeft.min(invoice.getTaxableValue());
            BigDecimal agency = feeLeft.min(invoice.getTaxableValue().subtract(retained));
            chargeLeft = chargeLeft.subtract(retained);
            feeLeft = feeLeft.subtract(agency);
            credits.add(new Credit(invoice, retained.add(agency), retained));
        }
        return credits;
    }

    /**
     * Money the client paid for this booking that still sits on their account as an advance held
     * for it (see PaymentReceiptService#availableWalletFor) - before the invoice, or what an
     * over-payment left after it. It is refundable on cancellation like any other.
     */
    private BigDecimal held(Booking booking) {
        return nz(paymentReceiptService.heldForBooking(booking.getClientId(), booking.getId()));
    }

    /** What the agency would still owe its suppliers after the cancellation: the part of the charge not already paid. */
    private static BigDecimal supplierChargeOwed(List<SupplierInvoice> bills, BigDecimal charge) {
        BigDecimal billed = bills.stream().map(SupplierInvoice::getGrandTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal paid = bills.stream().map(SupplierInvoice::getAmountPaid).reduce(BigDecimal.ZERO, BigDecimal::add);
        return charge.min(billed).subtract(paid).max(BigDecimal.ZERO);
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
        String ref = BookingService.bookingRef(b.getBookingNumber());
        return (b.getDestination() != null ? b.getDestination() + " " : "") + "(" + (ref != null ? ref : b.getId()) + ")";
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
