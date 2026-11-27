package com.voyra.crm.service;

import com.voyra.crm.dto.BookingDocumentCreateRequest;
import com.voyra.crm.dto.BookingResponse;
import com.voyra.crm.dto.PassengerCancelRequest;
import com.voyra.crm.dto.VoucherTraveller;
import com.voyra.crm.entity.Booking;
import com.voyra.crm.entity.BookingDocument;
import com.voyra.crm.enums.BookingDocumentType;
import com.voyra.crm.enums.BookingStatus;
import com.voyra.crm.enums.BookingType;
import com.voyra.crm.enums.MarkupMode;
import com.voyra.crm.exception.BookingConflictException;
import com.voyra.crm.repository.BookingDocumentRepository;
import com.voyra.crm.repository.BookingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Cancels some travellers of an agency-logged flight ticket while the booking stays live for the rest (client 27 Nov).
 * Only before the booking is invoiced: the ticket's voucher is lowered through the ordinary voucher edit, so the supplier
 * bill and the booking's price follow and the first invoice bills only what is kept. Its cost drops by the travellers'
 * purchase share less what the airline and the supplier keep; its price by their selling share less every charge (no GST
 * on them, owner ruling 2026-11-27). An invoiced booking is refused: its credit note is Accounts' step.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PassengerCancellationService {

    private final BookingService bookingService;
    private final BookingDocumentService documentService;
    private final BookingDocumentRepository documentRepository;
    private final BookingRepository bookingRepository;
    private final BookingActivityService activityService;

    @Transactional
    public BookingResponse cancelPassengers(String bookingId, PassengerCancelRequest request) {
        Booking booking = bookingService.lockForVoucher(bookingId);
        if (booking.getType() != BookingType.FLIGHT || booking.getPlatformBookingId() != null) {
            throw new BookingConflictException("NOT_PARTLY_CANCELLABLE",
                    "Only a flight you logged can have some passengers cancelled; a Voyra booking is cancelled through a request to Voyra", Map.of());
        }
        if (booking.getBookingStatus() != BookingStatus.CONFIRMED && booking.getBookingStatus() != BookingStatus.COMPLETED) {
            throw new BookingConflictException("NOT_PARTLY_CANCELLABLE", "Only a confirmed booking can have some passengers cancelled", Map.of());
        }
        if (bookingService.invoiced(bookingId).price().signum() > 0) {
            throw new BookingConflictException("ALREADY_INVOICED",
                    "This booking is invoiced - cancel its passengers with a credit note in Accounts", Map.of());
        }
        Set<String> picked = new HashSet<>(request.getTravellerIds());
        BookingDocument ticket = documentRepository.findByBookingIdOrderBySortOrderAsc(bookingId).stream()
                .filter(d -> d.getDocType() == BookingDocumentType.E_TICKET && d.getTravellers() != null)
                .filter(d -> live(d).containsAll(picked))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Pick travellers still flying on one ticket of this booking"));
        if (picked.size() >= live(ticket).size()) {
            throw new IllegalArgumentException("That is everyone on the ticket - cancel the whole booking instead");
        }

        BigDecimal supplierKeeps = nz(request.getAirlineCharge()).add(nz(request.getSupplierCharge()));
        BigDecimal kept = supplierKeeps.add(nz(request.getAgencyFee()));
        if (request.getPurchaseShare().compareTo(nz(ticket.getNetCost())) > 0
                || request.getSellingShare().compareTo(nz(ticket.getSellingPrice())) > 0) {
            throw new IllegalArgumentException("The share can't be more than the ticket's price");
        }
        if (supplierKeeps.compareTo(request.getPurchaseShare()) > 0 || kept.compareTo(request.getSellingShare()) > 0) {
            throw new IllegalArgumentException("The charges can't be more than the cancelled share");
        }
        BigDecimal netCost = nz(ticket.getNetCost()).subtract(request.getPurchaseShare()).add(supplierKeeps);
        BigDecimal price = nz(ticket.getSellingPrice()).subtract(request.getSellingShare()).add(kept);
        if (price.compareTo(netCost) < 0) {
            // the ordinary voucher edit refuses a negative markup too
            throw new IllegalArgumentException("Those shares would sell the rest of the ticket below its cost - check the selling and purchase shares");
        }

        Set<String> cancelled = new HashSet<>(picked);
        ticket.getTravellers().stream().filter(t -> Boolean.TRUE.equals(t.getCancelled())).map(VoucherTraveller::getMemberId).forEach(cancelled::add);
        documentService.updateDocument(bookingId, ticket.getId(), lowered(ticket, netCost, price));
        BookingDocument saved = documentRepository.findByIdAndBookingId(ticket.getId(), bookingId).orElseThrow();
        saved.setTravellers(saved.getTravellers().stream()
                .map(t -> t.toBuilder().cancelled(cancelled.contains(t.getMemberId()) ? Boolean.TRUE : null).build()).toList());
        documentRepository.save(saved);

        Booking fresh = bookingRepository.findById(bookingId).orElseThrow();
        fresh.setPartlyCancelledAt(LocalDateTime.now());
        // still owed when the rest is cancelled: a full cancel adds them to its own charges
        fresh.setPartialSupplierCharge(nz(fresh.getPartialSupplierCharge()).add(supplierKeeps));
        fresh.setPartialAgencyFee(nz(fresh.getPartialAgencyFee()).add(nz(request.getAgencyFee())));
        bookingRepository.save(fresh);
        String names = saved.getTravellers().stream().filter(t -> picked.contains(t.getMemberId())).map(VoucherTraveller::getName)
                .filter(Objects::nonNull).reduce((a, b) -> a + ", " + b).orElse("");
        activityService.record(bookingId, "PASSENGERS_CANCELLED", "Cancelled " + names + " on ticket " + Objects.toString(ticket.getReferenceNumber(), "")
                + ": share " + money(request.getSellingShare()) + ", kept " + money(kept)
                + (request.getReason() == null || request.getReason().isBlank() ? "" : " (" + request.getReason().trim() + ")"));
        log.info("Passengers cancelled: bookingId={}, documentId={}, travellers={}", bookingId, ticket.getId(), picked.size());
        return bookingService.toResponsePublic(fresh);
    }

    /** The travellers on the ticket not cancelled yet. */
    private static Set<String> live(BookingDocument d) {
        Set<String> out = new HashSet<>();
        d.getTravellers().stream().filter(t -> !Boolean.TRUE.equals(t.getCancelled())).forEach(t -> out.add(t.getMemberId()));
        return out;
    }

    /** The ticket as it is, at its new cost and price: still bought in its own currency, the markup now a flat base figure. */
    private static BookingDocumentCreateRequest lowered(BookingDocument d, BigDecimal netCost, BigDecimal price) {
        BookingDocumentCreateRequest r = new BookingDocumentCreateRequest();
        r.setDocType(d.getDocType());
        r.setReferenceNumber(d.getReferenceNumber());
        r.setIssuedDate(d.getIssuedDate());
        r.setNotes(d.getNotes());
        r.setVendorId(d.getVendorId());
        r.setDetails(d.getDetails());
        r.setLegs(d.getLegs());
        r.setTravellers(d.getTravellers().stream().map(t -> VoucherTraveller.builder().memberId(t.getMemberId()).build()).toList());
        if (d.getPurchaseCurrency() != null && d.getPurchaseRate() != null && d.getPurchaseRate().signum() > 0) {
            r.setPurchaseCurrency(d.getPurchaseCurrency());
            r.setPurchaseRate(d.getPurchaseRate());
            r.setPurchasePrice(netCost.divide(d.getPurchaseRate(), 2, RoundingMode.HALF_UP));
        } else {
            r.setNetCost(netCost);
        }
        r.setMarkupMode(MarkupMode.FLAT);
        r.setMarkupValue(price.subtract(netCost));
        return r;
    }

    private static String money(BigDecimal v) {
        return "₹" + String.format(java.util.Locale.ENGLISH, "%,.2f", v);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
