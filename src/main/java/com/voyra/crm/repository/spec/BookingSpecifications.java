package com.voyra.crm.repository.spec;

import com.voyra.crm.entity.Booking;
import com.voyra.crm.enums.BookingStatus;
import com.voyra.crm.enums.BookingType;
import com.voyra.crm.enums.ServiceType;
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
