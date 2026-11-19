package com.voyra.crm.service;

import com.voyra.crm.entity.AgencyApplication;
import com.voyra.crm.entity.PlatformBooking;
import com.voyra.crm.entity.PlatformCancelRequest;
import com.voyra.crm.entity.SubscriptionInvoice;
import com.voyra.crm.entity.TopupRequest;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Outbound notices. {@link EmailNotificationService} when SMTP_HOST is set (queued after the caller's commit, never inside it),
 * otherwise {@link LoggingNotificationService}. Implementations never throw into the caller's transaction.
 */
public interface NotificationService {

    /** To every active platform admin. */
    void platformAlert(String subject, String body);

    /** To the agency owner, 14/7/1 days before a trial, waiver or paid period ends; {@code kind} is TRIAL, WAIVER or PERIOD. */
    void subscriptionExpiring(String tenantId, String kind, Instant endsAt, int daysLeft);

    /** To the agency owner, when a subscription invoice is raised. */
    void subscriptionInvoiceIssued(SubscriptionInvoice invoice);

    /** To the agency owner, when a top-up is credited (bank transfer approved or online payment captured). */
    void topupApproved(TopupRequest topup);

    /** To the agency owner. */
    void topupRejected(TopupRequest topup);

    /** To the applicant, with the link that re-opens the form ({@code resumePath} is relative, e.g. /apply?application=..&token=..). */
    void applicationMoreInfo(AgencyApplication application, String resumePath);

    /** To the applicant. */
    void applicationRejected(AgencyApplication application);

    /** To the applicant (now the agency owner). Never carries the password. */
    void applicationApproved(AgencyApplication application);

    /** To whoever booked it. */
    void bookingTicketed(PlatformBooking booking);

    /** To whoever booked it. */
    void bookingFailed(PlatformBooking booking);

    /** To whoever booked it. {@code refundOffline}: the wallet was closed, nothing was posted, Voyra refunds by bank. */
    void bookingCancelled(PlatformBooking booking, boolean refundOffline);

    /** To every active platform admin: an agency asked Voyra to cancel (part of) a booking. */
    void cancelRequestRaised(PlatformBooking booking, PlatformCancelRequest request, String agencyName);

    /** To whoever booked it: Voyra cancelled what the request asked; {@code totalRefund} is every refund on the booking so far. */
    void cancelRequestCompleted(PlatformBooking booking, PlatformCancelRequest request, BigDecimal totalRefund);

    /** To whoever booked it: Voyra declined the request, with its note. */
    void cancelRequestRejected(PlatformBooking booking, PlatformCancelRequest request);
}
