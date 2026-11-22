package com.voyra.crm.repository.spec;

import com.voyra.crm.entity.Booking;
import jakarta.persistence.criteria.Predicate;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class CancellationSpecificationsTest {

    private final CriteriaRecorder rec = new CriteriaRecorder();

    private String build(Specification<Booking> spec) {
        Predicate p = spec.toPredicate(rec.root(), rec.query, rec.cb);
        return rec.render(p);
    }

    private static final String CANCELLED_SHOWN = "and(equal(root.bookingStatus, CANCELLED), not(and(isNotNull(root.platformBookingId), "
            + "equal(root.cancelledBy, system), isNull(root.cancellationType))))";
    private static final String REQUEST = "exists(query.subquery(String))";

    @Test
    void theTabListsCancelledBookingsAndOnesWithARequestOrAFollowUp() {
        assertThat(build(CancellationSpecifications.onTab("T1")))
                .isEqualTo("or(" + CANCELLED_SHOWN + ", " + REQUEST + ", " + REQUEST + ")");
        assertThat(rec.calls()).contains("PlatformCancelRequest", "equal(PlatformCancelRequest.platformBookingId, root.platformBookingId)",
                "equal(PlatformCancelRequest.tenantId, T1)", "CancellationFollowUp", "equal(CancellationFollowUp.bookingId, root.id)");
        assertThat(rec.calls()).noneMatch(c -> c.contains("CancellationFollowUp.status"));
    }

    @Test
    void openIsAMovingRefundARequestUnderWayOrALiveOpenFollowUp() {
        assertThat(build(CancellationSpecifications.open("T1"))).isEqualTo("or(and(" + CANCELLED_SHOWN + ", or(lessThan(root.refundedTotalInr, "
                + "root.customerRefundDue), lessThan(root.supplierRefundReceived, root.supplierRefundExpected))), " + REQUEST + ", " + REQUEST + ")");
        assertThat(rec.calls()).contains("equal(CancellationFollowUp.status, OPEN)", "equal(CancellationFollowUp.kind, FOLLOW_UP)",
                "notEqual(root.bookingStatus, CANCELLED)");
        assertThat(rec.calls()).anyMatch(c -> c.startsWith("PlatformCancelRequest.status.in(") && c.contains("OPEN") && c.contains("COMPLETING"));
    }

    @Test
    void dueIsAnOpenRequestOrAFollowUpDatedTodayOrEarlier() {
        assertThat(build(CancellationSpecifications.due("T1", LocalDate.of(2026, 11, 22)))).isEqualTo("or(" + REQUEST + ", " + REQUEST + ")");
        assertThat(rec.calls()).contains("PlatformCancelRequest.status.in([OPEN])", "lessThanOrEqualTo(CancellationFollowUp.dueDate, 2026-11-22)");
    }

    @Test
    void assignedToIsAnOpenFollowUpGivenToThatPerson() {
        assertThat(build(CancellationSpecifications.openFollowUpAssignedTo("A1"))).isEqualTo(REQUEST);
        assertThat(rec.calls()).contains("equal(CancellationFollowUp.assignedAgentId, A1)", "equal(CancellationFollowUp.status, OPEN)");
        assertThat(rec.calls()).noneMatch(c -> c.equals("PlatformCancelRequest"));
    }

    @Test
    void noActiveVoyraRequestIsTheNegatedExists() {
        assertThat(build(CancellationSpecifications.noActiveVoyraRequest("T1"))).isEqualTo("not(" + REQUEST + ")");
        assertThat(rec.calls()).anyMatch(c -> c.startsWith("PlatformCancelRequest.status.in(") && c.contains("IN_PROGRESS"));
    }
}
