package com.voyra.crm.service;

import com.voyra.crm.dto.InvoiceLineItemRequest;
import com.voyra.crm.entity.Booking;
import com.voyra.crm.entity.BookingDocument;
import com.voyra.crm.entity.BookingPassenger;
import com.voyra.crm.entity.BookingSector;
import com.voyra.crm.entity.Invoice;
import com.voyra.crm.entity.InvoiceLineItem;
import com.voyra.crm.enums.InvoiceDocumentType;
import com.voyra.crm.enums.InvoiceLifecycle;
import com.voyra.crm.enums.InvoiceServiceCategory;
import com.voyra.crm.repository.BookingDocumentRepository;
import com.voyra.crm.repository.BookingPassengerRepository;
import com.voyra.crm.repository.BookingSectorRepository;
import com.voyra.crm.repository.InvoiceLineItemRepository;
import com.voyra.crm.repository.InvoiceRepository;
import com.voyra.crm.util.BookingInvoiceLineBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * What of a booking is already invoiced, and the lines a new invoice bills it with. A voucher is
 * invoiced once an issued invoice line names it; an invoice that bills the booking without naming
 * vouchers (one uploaded from the agency's own software) covers every voucher added before it.
 */
@Service
@RequiredArgsConstructor
public class InvoiceLineSource {

    private final InvoiceRepository invoiceRepository;
    private final InvoiceLineItemRepository invoiceLineItemRepository;
    private final BookingDocumentRepository bookingDocumentRepository;
    private final BookingPassengerRepository bookingPassengerRepository;
    private final BookingSectorRepository bookingSectorRepository;

    /** The booking's billed price (before tax) on issued invoices, and which of its vouchers they cover. */
    public record Invoiced(BigDecimal price, Set<String> voucherIds, LocalDateTime unnamedAt) {
        public boolean covers(BookingDocument d) {
            return (d.getId() != null && voucherIds.contains(d.getId()))
                    || (unnamedAt != null && d.getCreatedAt() != null && !d.getCreatedAt().isAfter(unnamedAt));
        }
    }

    /** The lines for one booking on a new invoice, and the supplier cost they pass through untaxed. */
    public record Lines(List<InvoiceLineItemRequest> lines, BigDecimal passThrough) {
    }

    @Transactional(readOnly = true)
    public Invoiced invoiced(String bookingId) {
        BigDecimal price = BigDecimal.ZERO;
        Set<String> ids = new HashSet<>();
        LocalDateTime unnamedAt = null;
        for (Invoice invoice : invoiceRepository.findByBookingIdAndDocumentTypeAndStatusNotOrderByCreatedAtAsc(
                bookingId, InvoiceDocumentType.TAX_INVOICE, InvoiceLifecycle.CANCELLED)) {
            if (invoice.getStatus() == InvoiceLifecycle.DRAFT) continue;
            List<InvoiceLineItem> mine = linesOf(invoice, bookingId);
            price = price.add(basePrice(invoice, mine));
            mine.stream().map(InvoiceLineItem::getBookingDocumentId).filter(java.util.Objects::nonNull).forEach(ids::add);
            boolean named = mine.stream().anyMatch(l -> l.getBookingDocumentId() != null);
            if (!mine.isEmpty() && !named && invoice.getIssuedAt() != null
                    && (unnamedAt == null || invoice.getIssuedAt().isAfter(unnamedAt))) {
                unnamedAt = invoice.getIssuedAt();
            }
        }
        return new Invoiced(price, ids, unnamedAt);
    }

    /**
     * What these lines bill, in base. An invoice in another currency carried its vouchers at its own rate, so a
     * voucher it names counts at its own base price (converting back would leave paise "still to bill"); a line
     * naming none counts at the invoice's rate.
     */
    private BigDecimal basePrice(Invoice invoice, List<InvoiceLineItem> lines) {
        BigDecimal fx = invoice.getFxRateToInr();
        if (fx == null || fx.compareTo(BigDecimal.ONE) == 0) {
            return lines.stream().map(l -> nz(l.getLineSubtotal())).reduce(BigDecimal.ZERO, BigDecimal::add);
        }
        BigDecimal unnamed = lines.stream().filter(l -> l.getBookingDocumentId() == null)
                .map(l -> nz(l.getLineSubtotal()).multiply(fx).setScale(2, java.math.RoundingMode.HALF_UP))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<String> named = lines.stream().map(InvoiceLineItem::getBookingDocumentId).filter(java.util.Objects::nonNull).distinct().toList();
        BigDecimal vouchers = named.isEmpty() ? BigDecimal.ZERO : bookingDocumentRepository.findAllById(named).stream()
                .map(d -> nz(d.getSellingPrice())).reduce(BigDecimal.ZERO, BigDecimal::add);
        return unnamed.add(vouchers);
    }

    /** The live draft holding this booking, if any. */
    @Transactional(readOnly = true)
    public Invoice draftOf(String bookingId) {
        return invoiceRepository.findDraftsForBooking(bookingId).stream().findFirst().orElse(null);
    }

    /** A voucher's new fare breakdown onto the live draft line billing it (a voucher billed per traveller has none). */
    @Transactional
    public void refreshDraftCharges(BookingDocument voucher) {
        for (Invoice draft : invoiceRepository.findDraftsForBooking(voucher.getBookingId())) {
            List<InvoiceLineItem> lines = linesOf(draft, voucher.getBookingId()).stream()
                    .filter(l -> voucher.getId().equals(l.getBookingDocumentId())).toList();
            if (lines.size() != 1) continue;
            lines.get(0).setCharges(com.voyra.crm.util.VoucherCharges.toBase(voucher.getCharges(), voucher.getPurchaseRate(), voucher.getNetCost()));
            invoiceLineItemRepository.save(lines.get(0));
        }
    }

    /** Vouchers billed by a live draft - they stay as they are until that draft is generated or deleted. */
    @Transactional(readOnly = true)
    public Set<String> draftVoucherIds(String bookingId) {
        return invoiceRepository.findDraftsForBooking(bookingId).stream()
                .flatMap(i -> linesOf(i, bookingId).stream())
                .map(InvoiceLineItem::getBookingDocumentId).filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
    }

    /**
     * One line per voucher not yet invoiced when they make up what is left to bill (a single voucher on a
     * booking with travellers is billed per traveller instead, when their fares make up the price);
     * otherwise one line for what is left. Empty when nothing is left to bill.
     */
    @Transactional(readOnly = true)
    public Lines linesFor(Booking booking, InvoiceServiceCategory category) {
        Invoiced invoiced = invoiced(booking.getId());
        BigDecimal left = nz(booking.getSellingPrice()).subtract(invoiced.price());
        if (left.signum() <= 0) {
            return new Lines(List.of(), BigDecimal.ZERO);
        }
        List<BookingDocument> vouchers = bookingDocumentRepository.findByBookingIdOrderBySortOrderAsc(booking.getId());
        List<BookingDocument> open = vouchers.stream()
                .filter(d -> nz(d.getSellingPrice()).signum() > 0 && !invoiced.covers(d)).toList();
        BigDecimal openPrice = open.stream().map(d -> nz(d.getSellingPrice())).reduce(BigDecimal.ZERO, BigDecimal::add);
        // Money still on the booking row rather than on a voucher (a booking from before vouchers carried it).
        BigDecimal unvouchered = nz(booking.getNetCost())
                .subtract(vouchers.stream().map(d -> nz(d.getNetCost())).reduce(BigDecimal.ZERO, BigDecimal::add)).max(BigDecimal.ZERO);
        BigDecimal passThrough = open.stream().map(d -> nz(d.getNetCost())).reduce(BigDecimal.ZERO, BigDecimal::add).add(unvouchered);

        List<InvoiceLineItemRequest> lines;
        if (!open.isEmpty() && openPrice.compareTo(left) == 0) {
            // An air ticket is one line naming its travellers and flights; other bookings may still bill per traveller.
            lines = open.size() == 1 && invoiced.price().signum() == 0 && booking.getType() != com.voyra.crm.enums.BookingType.FLIGHT
                    ? travellerLines(booking, category, open.get(0)) : null;
            if (lines == null) {
                lines = open.stream().map(d -> {
                    InvoiceLineItemRequest l = line(booking, d.getId(), describe(booking, d), d.getSellingPrice());
                    l.setCharges(com.voyra.crm.util.VoucherCharges.toBase(d.getCharges(), d.getPurchaseRate(), d.getNetCost()));
                    l.setTravellers(d.getTravellers());
                    l.setLegs(d.getLegs());
                    l.setPurchaseCurrency(d.getPurchaseCurrency());
                    l.setPurchaseRate(d.getPurchaseRate());
                    return l;
                }).toList();
            }
        } else {
            lines = List.of(line(booking, null, "Services on " + BookingService.bookingRef(booking.getBookingNumber())
                    + (booking.getDestination() != null ? " - " + booking.getDestination() : ""), left));
        }
        return new Lines(lines, passThrough.min(left));
    }

    /** Lines billing {@code bookingId} on an invoice; lines with no booking belong to the invoice's first. */
    public List<InvoiceLineItem> linesOf(Invoice invoice, String bookingId) {
        return invoiceLineItemRepository.findByInvoiceIdOrderBySortOrderAsc(invoice.getId()).stream()
                .filter(l -> bookingId.equals(l.getBookingId() != null ? l.getBookingId() : invoice.getBookingId()))
                .toList();
    }

    /** Every booking an invoice bills, its first booking first. */
    public List<String> bookingIdsOf(Invoice invoice) {
        List<String> ids = new ArrayList<>();
        if (invoice.getBookingId() != null) ids.add(invoice.getBookingId());
        invoiceLineItemRepository.findBookingIdsByInvoiceId(invoice.getId()).stream().filter(id -> !ids.contains(id)).forEach(ids::add);
        return ids;
    }

    /** Per traveller, when the booking has travellers whose fares make up its price; null otherwise. */
    private List<InvoiceLineItemRequest> travellerLines(Booking booking, InvoiceServiceCategory category, BookingDocument voucher) {
        List<BookingPassenger> passengers = bookingPassengerRepository.findByBookingIdOrderBySortOrderAsc(booking.getId());
        if (!category.isPassengerWise() || passengers.isEmpty()) return null;
        Map<String, List<BookingSector>> sectors = bookingSectorRepository.findByBookingPassengerIdInOrderBySortOrderAsc(
                passengers.stream().map(BookingPassenger::getId).toList()).stream()
                .collect(Collectors.groupingBy(BookingSector::getBookingPassengerId));
        List<InvoiceLineItemRequest> lines = BookingInvoiceLineBuilder.build(booking, category, passengers, sectors);
        BigDecimal total = lines.stream().map(l -> nz(l.getUnitPrice()).multiply(l.getQuantity())).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.compareTo(nz(voucher.getSellingPrice())) != 0) return null;
        lines.forEach(l -> {
            l.setBookingId(booking.getId());
            l.setBookingDocumentId(voucher.getId());
        });
        return lines;
    }

    private static InvoiceLineItemRequest line(Booking booking, String voucherId, String description, BigDecimal price) {
        InvoiceLineItemRequest line = new InvoiceLineItemRequest();
        line.setBookingId(booking.getId());
        line.setBookingDocumentId(voucherId);
        line.setDescription(description.length() > 500 ? description.substring(0, 500) : description);
        line.setServiceType(booking.getServiceType());
        line.setQuantity(BigDecimal.ONE);
        line.setUnitPrice(price);
        return line;
    }

    /** e.g. "BOM to DXB 19/10/26 (NAHKSJ) - MUKESH, SHEETAL". */
    private String describe(Booking booking, BookingDocument d) {
        String label = voucherLabel(d);
        Stream<String> names = d.getTravellers() != null && !d.getTravellers().isEmpty()
                ? d.getTravellers().stream().map(com.voyra.crm.dto.VoucherTraveller::getName)
                : bookingPassengerRepository.findByBookingIdOrderBySortOrderAsc(booking.getId()).stream().map(BookingPassenger::getPassengerName);
        String pax = names.filter(n -> n != null && !n.isBlank()).distinct().collect(Collectors.joining(", "));
        String what = label.isEmpty() ? (booking.getDestination() != null ? booking.getDestination() : booking.getType().name()) : label;
        return pax.isEmpty() ? what : what + " - " + pax;
    }

    /** A voucher in a few words: what, when and its reference, e.g. "BOM to DEL 20/12/26 (RET1)". */
    static String voucherLabel(BookingDocument d) {
        Map<String, String> x = d.getDetails() == null ? Map.of() : d.getDetails();
        // Every stop of the trip from its legs, so a return BOM-DXB-BOM doesn't read "BOM to BOM".
        List<String> stops = d.getLegs() == null || d.getLegs().isEmpty() ? List.of()
                : Stream.concat(Stream.of(d.getLegs().get(0).getFrom()), d.getLegs().stream().map(com.voyra.crm.dto.VoucherLeg::getTo))
                        .filter(c -> c != null && !c.isBlank()).toList();
        String what = stops.size() > 2 ? String.join(" - ", stops)
                : x.get("flightFrom") != null && x.get("flightTo") != null ? x.get("flightFrom") + " to " + x.get("flightTo")
                : x.getOrDefault("hotelName", x.getOrDefault("transferPickup", x.getOrDefault("visaCountry", "")));
        String when = x.getOrDefault("journeyDate", x.getOrDefault("hotelCheckIn", x.getOrDefault("transferDate", "")));
        when = when.isBlank() ? when : BookingInvoiceLineBuilder.lineDate(when);
        String ref = d.getReferenceNumber() == null || d.getReferenceNumber().isBlank() ? "" : "(" + d.getReferenceNumber().trim() + ")";
        return Stream.of(what, when, ref).filter(p -> !p.isBlank()).collect(Collectors.joining(" "));
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}
