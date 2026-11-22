package com.voyra.crm.util;

import com.voyra.crm.entity.Booking;
import com.voyra.crm.entity.CancellationFollowUp;
import com.voyra.crm.enums.BookingStatus;
import com.voyra.crm.enums.CancelRequestStatus;
import com.voyra.crm.enums.CancellationFollowUpKind;
import com.voyra.crm.enums.CancellationStatus;
import com.voyra.crm.enums.FollowUpStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** The one place a booking's {@link CancellationStatus} is worked out. */
public final class CancellationStatusRules {

    private CancellationStatusRules() {
    }

    /**
     * @param newestRequest the status of the booking's newest Voyra cancel request, null when it has none
     * @param followUps     the booking's follow-ups, any status
     */
    public static CancellationStatus of(Booking b, CancelRequestStatus newestRequest, List<CancellationFollowUp> followUps, LocalDate today) {
        if (b.getBookingStatus() == BookingStatus.CANCELLED) {
            if (refundMoving(b)) {
                return CancellationStatus.REFUND_PENDING;
            }
            return positive(b.getCustomerRefundDue()) || positive(b.getSupplierRefundExpected())
                    ? CancellationStatus.REFUND_PROCESSED : CancellationStatus.CANCELLED;
        }
        if (newestRequest == CancelRequestStatus.OPEN) {
            return CancellationStatus.REQUESTED;
        }
        if (newestRequest != null && CancelRequestStatus.ACTIVE.contains(newestRequest)) {
            return CancellationStatus.IN_PROGRESS;
        }
        LocalDate scheduled = followUps.stream()
                .filter(f -> f.getKind() == CancellationFollowUpKind.SCHEDULED_CANCEL && f.getStatus() == FollowUpStatus.OPEN)
                .map(CancellationFollowUp::getDueDate).min(LocalDate::compareTo).orElse(null);
        if (scheduled != null) {
            return scheduled.isAfter(today) ? CancellationStatus.CANCELLATION_PENDING : CancellationStatus.FOLLOW_UP_REQUIRED;
        }
        if (newestRequest == CancelRequestStatus.REJECTED) {
            return CancellationStatus.FAILED;
        }
        if (newestRequest == CancelRequestStatus.WITHDRAWN) {
            return CancellationStatus.WITHDRAWN;
        }
        return newestRequest == CancelRequestStatus.COMPLETED ? CancellationStatus.PARTLY_CANCELLED : CancellationStatus.NOT_CANCELLED;
    }

    /** A follow-up of the other kind is due: an open one dated today or earlier. */
    public static boolean followUpDue(List<CancellationFollowUp> followUps, LocalDate today) {
        return followUps.stream().anyMatch(f -> f.getKind() == CancellationFollowUpKind.FOLLOW_UP
                && f.getStatus() == FollowUpStatus.OPEN && !f.getDueDate().isAfter(today));
    }

    /** Money the client is owed, or the supplier owes, has not moved yet (the cross-check is Accounts' step after). */
    public static boolean refundMoving(Booking b) {
        return nz(b.getRefundedTotalInr()).compareTo(nz(b.getCustomerRefundDue())) < 0
                || nz(b.getSupplierRefundReceived()).compareTo(nz(b.getSupplierRefundExpected())) < 0;
    }

    private static boolean positive(BigDecimal v) {
        return nz(v).signum() > 0;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
