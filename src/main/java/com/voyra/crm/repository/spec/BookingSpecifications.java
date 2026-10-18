package com.voyra.crm.repository.spec;

import com.voyra.crm.entity.Booking;
import com.voyra.crm.enums.BookingStatus;
import com.voyra.crm.enums.BookingType;
import com.voyra.crm.enums.ServiceType;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Built as {@code Specification}s rather than a hand-written {@code (:param IS NULL OR ...)}
 * JPQL query - see {@code AuditLogRepository}'s javadoc for why that pattern breaks Postgres
 * ("could not determine data type of parameter") once a {@code Pageable} is involved. A
 * Specification only adds a predicate for a filter that is actually supplied.
 */
public final class BookingSpecifications {

    private BookingSpecifications() {
    }

    /**
     * The SQL translation of {@link com.voyra.crm.util.BookingAccessChecker#agentCanAccess} -
     * the two must agree, and {@code BookingScopeTest} asserts they do on the same fixture set.
     * A standalone booking (service_type null) can only ever match the first clause.
     */
    public static Specification<Booking> accessibleToAgent(String agentId, Collection<ServiceType> manageableTypes) {
        return (root, query, cb) -> {
            List<Predicate> or = new ArrayList<>();
            or.add(cb.equal(root.get("agentId"), agentId));
            or.add(cb.equal(root.get("serviceAgentId"), agentId));
            if (!manageableTypes.isEmpty()) {
                or.add(root.get("serviceType").in(manageableTypes));
            }
            return cb.or(or.toArray(new Predicate[0]));
        };
    }

    /**
     * Client money still open: a live booking not yet invoiced or not fully collected, or a
     * cancelled one with a refund still to move or to cross-check. Mirrors the money half of
     * {@link com.voyra.crm.util.BookingNextStep}.
     */
    public static Specification<Booking> moneyPending() {
        return (root, query, cb) -> {
            var cancelled = cb.equal(root.get("bookingStatus"), BookingStatus.CANCELLED);
            var live = cb.and(cb.not(cancelled), cb.gt(root.get("sellingPrice"), 0), cb.or(
                    cb.equal(root.get("invoicedTotalInr"), 0),
                    cb.lessThan(root.get("receivedTotalInr"), root.get("invoicedTotalInr"))));
            var refundOpen = cb.and(cancelled, cb.or(
                    cb.lessThan(root.get("refundedTotalInr"), root.get("customerRefundDue")),
                    cb.lessThan(root.get("supplierRefundReceived"), root.get("supplierRefundExpected")),
                    cb.and(cb.isNull(root.get("refundCheckedAt")), cb.or(
                            cb.gt(root.get("customerRefundDue"), 0), cb.gt(root.get("supplierRefundExpected"), 0)))));
            return cb.or(live, refundOpen);
        };
    }

    public static Specification<Booking> typeIs(BookingType type) {
        return (root, query, cb) -> cb.equal(root.get("type"), type);
    }

    public static Specification<Booking> statusIs(BookingStatus status) {
        return (root, query, cb) -> cb.equal(root.get("bookingStatus"), status);
    }

    /** Case-insensitive substring match - destinations are free text, never an exact enum. */
    public static Specification<Booking> destinationContains(String destination) {
        return (root, query, cb) -> cb.like(cb.lower(root.get("destination")), "%" + destination.toLowerCase() + "%");
    }

    /**
     * One search box for the Bookings screen. A query that is a booking number ("42", "BK-42",
     * "bk-00042") matches that number, and is also read as the start of one as people type it:
     * "BK-001" finds BK-00100 to BK-00199. Anything else matches client name, destination, PNR
     * or a supplier reference, case-insensitively.
     */
    public static Specification<Booking> searchMatches(String rawQuery) {
        String q = rawQuery.trim().toLowerCase();
        String digits = q.startsWith("bk") ? q.substring(2).replaceAll("[^0-9]", "") : q;
        Long number = digits.matches("\\d{1,18}") ? Long.valueOf(digits) : null;
        String like = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        if (q.matches("bk[-\\s]*")) {
            // Just "BK-" so far - the number is still being typed, so don't empty the list.
            return (root, query, cb) -> cb.conjunction();
        }
        return (root, query, cb) -> {
            List<Predicate> or = new ArrayList<>();
            if (number != null) {
                or.add(cb.equal(root.get("bookingNumber"), number));
                // The number as it is shown (zero-padded to five), matched from the start.
                Expression<String> shown = cb.function("lpad", String.class,
                        root.get("bookingNumber").as(String.class), cb.literal(5), cb.literal("0"));
                or.add(cb.like(shown, digits + "%"));
            }
            for (String field : List.of("clientName", "destination", "pnr", "ticketNo", "hotelConfirmationNo",
                    "visaApplicationNo", "transferVoucherNo")) {
                or.add(cb.like(cb.lower(root.get(field)), like, '\\'));
            }
            // Any live voucher's own reference, so an edited or second voucher's ref finds its booking too.
            jakarta.persistence.criteria.Subquery<Long> voucher = query.subquery(Long.class);
            jakarta.persistence.criteria.Root<com.voyra.crm.entity.BookingDocument> doc =
                    voucher.from(com.voyra.crm.entity.BookingDocument.class);
            voucher.select(cb.literal(1L)).where(
                    cb.equal(doc.get("bookingId"), root.get("id")),
                    cb.like(cb.lower(doc.get("referenceNumber")), like, '\\'));
            or.add(cb.exists(voucher));
            return cb.or(or.toArray(new Predicate[0]));
        };
    }

    public static Specification<Booking> agentIs(String agentId) {
        return (root, query, cb) -> cb.equal(root.get("agentId"), agentId);
    }

    public static Specification<Booking> vendorIs(String vendorId) {
        return (root, query, cb) -> cb.equal(root.get("vendorId"), vendorId);
    }

    /**
     * FRD US-ACC-1.1's "Unbilled Bookings" workbench: CONFIRMED bookings with no live (non-
     * cancelled) invoice against them yet. A correlated NOT EXISTS subquery against
     * {@code Invoice}, so this is exactly two tables touched, no join blow-up.
     */
    public static Specification<Booking> confirmedAndUnbilled() {
        return (root, query, cb) -> {
            jakarta.persistence.criteria.Subquery<Long> sub = query.subquery(Long.class);
            jakarta.persistence.criteria.Root<com.voyra.crm.entity.Invoice> invoice =
                    sub.from(com.voyra.crm.entity.Invoice.class);
            sub.select(cb.literal(1L));
            sub.where(
                    cb.equal(invoice.get("bookingId"), root.get("id")),
                    cb.notEqual(invoice.get("status"), com.voyra.crm.enums.InvoiceLifecycle.CANCELLED));
            return cb.and(
                    cb.equal(root.get("bookingStatus"), BookingStatus.CONFIRMED),
                    cb.not(cb.exists(sub)));
        };
    }
}
