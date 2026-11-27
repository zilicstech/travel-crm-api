package com.voyra.crm.util;

import com.voyra.crm.entity.Booking;
import com.voyra.crm.entity.CancellationFollowUp;
import com.voyra.crm.enums.BookingStatus;
import com.voyra.crm.enums.CancelRequestStatus;
import com.voyra.crm.enums.CancellationFollowUpKind;
import com.voyra.crm.enums.CancellationStatus;
import com.voyra.crm.enums.FollowUpStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CancellationStatusRulesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 22);

    private static Booking live() {
        return Booking.builder().bookingStatus(BookingStatus.CONFIRMED).build();
    }

    private static Booking cancelled(String due, String refunded, String expected, String received) {
        Booking b = Booking.builder().bookingStatus(BookingStatus.CANCELLED).refundProcessedAt(java.time.LocalDateTime.now()).build();
        b.setCustomerRefundDue(due == null ? null : new BigDecimal(due));
        b.setRefundedTotalInr(refunded == null ? null : new BigDecimal(refunded));
        b.setSupplierRefundExpected(expected == null ? null : new BigDecimal(expected));
        b.setSupplierRefundReceived(received == null ? null : new BigDecimal(received));
        return b;
    }

    private static CancellationFollowUp f(CancellationFollowUpKind kind, LocalDate due, FollowUpStatus status) {
        return CancellationFollowUp.builder().kind(kind).dueDate(due).status(status).build();
    }

    private static CancellationStatus of(Booking b, CancelRequestStatus newest, CancellationFollowUp... followUps) {
        return CancellationStatusRules.of(b, newest, List.of(followUps), TODAY);
    }

    @Test
    void aCancelledBookingReadsByItsRefunds() {
        // marked processed: done, even while the books still show money to move (Accounts follows that)
        assertThat(of(cancelled("100", "40", "0", "0"), null)).isEqualTo(CancellationStatus.REFUND_PROCESSED);
        Booking unmarked = cancelled("0", "0", "90", "10");
        unmarked.setRefundProcessedAt(null);
        assertThat(of(unmarked, CancelRequestStatus.COMPLETED)).isEqualTo(CancellationStatus.REFUND_PENDING);
        assertThat(of(cancelled("100", "100", "90", "90"), null)).isEqualTo(CancellationStatus.REFUND_PROCESSED);
        assertThat(of(cancelled("0", "0", "90", "90"), null)).isEqualTo(CancellationStatus.REFUND_PROCESSED);
        assertThat(of(cancelled("0", "0", "0", "0"), null)).isEqualTo(CancellationStatus.CANCELLED);
        assertThat(of(cancelled(null, null, null, null), null)).isEqualTo(CancellationStatus.CANCELLED);
    }

    @Test
    void aLiveBookingWithSomePassengersCancelledIsPartlyCancelledUnlessVoyraOrAScheduleSaysMore() {
        Booking b = live();
        b.setPartlyCancelledAt(java.time.LocalDateTime.now());
        assertThat(of(b, null)).isEqualTo(CancellationStatus.PARTLY_CANCELLED);
        assertThat(of(b, CancelRequestStatus.REJECTED)).isEqualTo(CancellationStatus.PARTLY_CANCELLED);
        assertThat(of(b, CancelRequestStatus.OPEN)).isEqualTo(CancellationStatus.REQUESTED);
    }

    @Test
    void aCancellationNobodyMarkedProcessedIsStillPendingUntilMarkedOrCrossChecked() {
        Booking b = cancelled("0", "0", "0", "0");
        b.setRefundProcessedAt(null);
        assertThat(of(b, null)).isEqualTo(CancellationStatus.REFUND_PENDING);
        assertThat(CancellationStatusRules.closed(b)).isFalse();
        b.setRefundCheckedAt(java.time.LocalDateTime.now());
        assertThat(of(b, null)).isEqualTo(CancellationStatus.CANCELLED);
        assertThat(CancellationStatusRules.closed(b)).isTrue();
    }

    @Test
    void aRequestUnderWayWinsOverAScheduledCancellation() {
        CancellationFollowUp due = f(CancellationFollowUpKind.SCHEDULED_CANCEL, TODAY, FollowUpStatus.OPEN);
        assertThat(of(live(), CancelRequestStatus.OPEN, due)).isEqualTo(CancellationStatus.REQUESTED);
        assertThat(of(live(), CancelRequestStatus.IN_PROGRESS, due)).isEqualTo(CancellationStatus.IN_PROGRESS);
        assertThat(of(live(), CancelRequestStatus.COMPLETING)).isEqualTo(CancellationStatus.IN_PROGRESS);
    }

    @Test
    void theEarliestOpenScheduledCancellationIsPendingUntilItsDayThenNeedsFollowingUp() {
        CancellationFollowUp later = f(CancellationFollowUpKind.SCHEDULED_CANCEL, TODAY.plusDays(2), FollowUpStatus.OPEN);
        CancellationFollowUp today = f(CancellationFollowUpKind.SCHEDULED_CANCEL, TODAY, FollowUpStatus.OPEN);
        CancellationFollowUp closed = f(CancellationFollowUpKind.SCHEDULED_CANCEL, TODAY.minusDays(5), FollowUpStatus.DONE);
        CancellationFollowUp note = f(CancellationFollowUpKind.FOLLOW_UP, TODAY.minusDays(5), FollowUpStatus.OPEN);
        assertThat(of(live(), null, later, closed, note)).isEqualTo(CancellationStatus.CANCELLATION_PENDING);
        assertThat(of(live(), CancelRequestStatus.REJECTED, later, today)).isEqualTo(CancellationStatus.FOLLOW_UP_REQUIRED);
    }

    @Test
    void aFinishedRequestOnALiveBookingTellsWhatHappened() {
        assertThat(of(live(), CancelRequestStatus.REJECTED)).isEqualTo(CancellationStatus.FAILED);
        assertThat(of(live(), CancelRequestStatus.WITHDRAWN)).isEqualTo(CancellationStatus.WITHDRAWN);
        assertThat(of(live(), CancelRequestStatus.COMPLETED)).isEqualTo(CancellationStatus.PARTLY_CANCELLED);
        assertThat(of(live(), null, f(CancellationFollowUpKind.SCHEDULED_CANCEL, TODAY, FollowUpStatus.DONE)))
                .isEqualTo(CancellationStatus.NOT_CANCELLED);
    }

    @Test
    void anOpenFollowUpDatedTodayOrEarlierIsDue() {
        assertThat(CancellationStatusRules.followUpDue(List.of(f(CancellationFollowUpKind.FOLLOW_UP, TODAY, FollowUpStatus.OPEN)), TODAY)).isTrue();
        assertThat(CancellationStatusRules.followUpDue(List.of(
                f(CancellationFollowUpKind.FOLLOW_UP, TODAY.plusDays(1), FollowUpStatus.OPEN),
                f(CancellationFollowUpKind.FOLLOW_UP, TODAY.minusDays(1), FollowUpStatus.DONE),
                f(CancellationFollowUpKind.SCHEDULED_CANCEL, TODAY.minusDays(1), FollowUpStatus.OPEN)), TODAY)).isFalse();
    }
}
