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
}
