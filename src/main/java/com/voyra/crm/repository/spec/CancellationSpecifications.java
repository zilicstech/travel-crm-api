package com.voyra.crm.repository.spec;

import com.voyra.crm.entity.Booking;
import com.voyra.crm.entity.CancellationFollowUp;
import com.voyra.crm.entity.PlatformCancelRequest;
import com.voyra.crm.enums.BookingStatus;
import com.voyra.crm.enums.CancelRequestStatus;
import com.voyra.crm.enums.CancellationFollowUpKind;
import com.voyra.crm.enums.FollowUpStatus;
import com.voyra.crm.security.SystemActor;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Which bookings the Cancellations tab lists, in SQL so a page is a real page. Voyra requests live in the public
 * {@code platform_cancel_request}, reached through the tenant search_path and narrowed to this agency. Must agree with
 * {@link com.voyra.crm.util.CancellationStatusRules}: a scheduled cancellation counts only while the booking is live and
 * has no Voyra request under way.
 */
public final class CancellationSpecifications {

    private CancellationSpecifications() {
    }

    /** Cancelled (not a Voyra booking that simply failed), with a Voyra request, or with any follow-up. */
    public static Specification<Booking> onTab(String tenantId) {
        return (root, query, cb) -> cb.or(cancelledShown(root, cb), request(root, query, cb, tenantId, null),
                followUp(root, query, cb, tenantId, false, null, null), cb.isNotNull(root.get("partlyCancelledAt")));
    }

    /**
     * Still needs someone: a cancellation nobody has marked refund-processed and Accounts has not cross-checked
     * (client 27 Nov), a Voyra request under way, or an open follow-up.
     */
    public static Specification<Booking> open(String tenantId) {
        return (root, query, cb) -> cb.or(cb.and(cancelledShown(root, cb),
                        cb.isNull(root.get("refundProcessedAt")), cb.isNull(root.get("refundCheckedAt"))),
                cb.and(cb.isNotNull(root.get("partlyCancelledAt")), cb.isNull(root.get("refundProcessedAt"))),
                request(root, query, cb, tenantId, CancelRequestStatus.ACTIVE),
                followUp(root, query, cb, tenantId, true, null, null));
    }

    /** Due now: an open follow-up dated today or earlier, or a request Voyra has not picked up yet. */
    public static Specification<Booking> due(String tenantId, LocalDate today) {
        return (root, query, cb) -> cb.or(request(root, query, cb, tenantId, Set.of(CancelRequestStatus.OPEN)),
                followUp(root, query, cb, tenantId, true, today, null));
    }

    /** Has an open follow-up given to this person. */
    public static Specification<Booking> openFollowUpAssignedTo(String userId) {
        return (root, query, cb) -> followUp(root, query, cb, null, true, null, userId);
    }

    /** No Voyra request of this agency is under way on the booking (open, being worked or completing). */
    public static Specification<Booking> noActiveVoyraRequest(String tenantId) {
        return (root, query, cb) -> cb.not(request(root, query, cb, tenantId, CancelRequestStatus.ACTIVE));
    }

    private static Predicate cancelledShown(Root<Booking> root, CriteriaBuilder cb) {
        Predicate failCancelled = cb.and(cb.isNotNull(root.get("platformBookingId")),
                cb.equal(root.get("cancelledBy"), SystemActor.USER_ID), cb.isNull(root.get("cancellationType")));
        return cb.and(cb.equal(root.get("bookingStatus"), BookingStatus.CANCELLED), cb.not(failCancelled));
    }

    /** A Voyra request of this agency on the booking, in one of {@code statuses} (null = any). */
    private static Predicate request(Root<Booking> root, CriteriaQuery<?> query, CriteriaBuilder cb, String tenantId,
                                     Collection<CancelRequestStatus> statuses) {
        Subquery<String> sq = query.subquery(String.class);
        Root<PlatformCancelRequest> r = sq.from(PlatformCancelRequest.class);
        List<Predicate> where = new ArrayList<>(List.of(cb.equal(r.get("platformBookingId"), root.get("platformBookingId")),
                cb.equal(r.get("tenantId"), tenantId)));
        if (statuses != null) {
            where.add(r.get("status").in(statuses));
        }
        sq.select(r.get("id")).where(where.toArray(new Predicate[0]));
        return cb.exists(sq);
    }

    /**
     * A follow-up on the booking. {@code live}: open, and a scheduled cancellation only while the booking is live with no
     * Voyra request under way; {@code dueBy}: dated then or earlier; {@code assignee}: given to that person.
     */
    private static Predicate followUp(Root<Booking> root, CriteriaQuery<?> query, CriteriaBuilder cb, String tenantId,
                                      boolean live, LocalDate dueBy, String assignee) {
        Subquery<String> sq = query.subquery(String.class);
        Root<CancellationFollowUp> f = sq.from(CancellationFollowUp.class);
        List<Predicate> where = new ArrayList<>(List.of(cb.equal(f.get("bookingId"), root.get("id"))));
        if (live) {
            where.add(cb.equal(f.get("status"), FollowUpStatus.OPEN));
            Predicate stillToCancel = cb.notEqual(root.get("bookingStatus"), BookingStatus.CANCELLED);
            if (tenantId != null) {
                stillToCancel = cb.and(stillToCancel, cb.not(request(root, query, cb, tenantId, CancelRequestStatus.ACTIVE)));
            }
            where.add(cb.or(cb.equal(f.get("kind"), CancellationFollowUpKind.FOLLOW_UP), stillToCancel));
        }
        if (dueBy != null) {
            where.add(cb.lessThanOrEqualTo(f.get("dueDate"), dueBy));
        }
        if (assignee != null) {
            where.add(cb.equal(f.get("assignedAgentId"), assignee));
        }
        sq.select(f.get("id")).where(where.toArray(new Predicate[0]));
        return cb.exists(sq);
    }
}
