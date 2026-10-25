package com.voyra.crm.service;

import com.voyra.crm.config.SmtpConfigured;
import com.voyra.crm.entity.AgencyApplication;
import com.voyra.crm.entity.PlatformBooking;
import com.voyra.crm.entity.SubscriptionInvoice;
import com.voyra.crm.entity.TopupRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;

/** Used when SMTP_HOST is unset: one log line per notice, ids and amounts only (no emails, names or links). */
@Slf4j
@Service
@Conditional(SmtpConfigured.Not.class)
public class LoggingNotificationService implements NotificationService {

    public LoggingNotificationService() {
        log.info("SMTP_HOST is not set - notifications are logged only, no email is sent");
    }

    @Override
    public void lowBalance(String tenantId, BigDecimal available) {
        log.warn("Low wallet balance - tenantId: {}, available: {}", tenantId, available);
    }

    @Override
    public void platformAlert(String subject, String body) {
        log.warn("Platform alert - {}: {}", subject, body);
    }

    @Override
    public void subscriptionExpiring(String tenantId, String kind, Instant endsAt, int daysLeft) {
        log.warn("Subscription expiring - tenantId: {}, {} ends {} ({} day(s) left)", tenantId, kind, endsAt, daysLeft);
    }

    @Override
    public void subscriptionInvoiceIssued(SubscriptionInvoice invoice) {
        log.info("Subscription invoice issued - tenantId: {}, number: {}, total: {}", invoice.getTenantId(), invoice.getNumber(), invoice.getTotal());
    }

    @Override
    public void topupApproved(TopupRequest topup) {
        log.info("Top-up approved - tenantId: {}, topup: {}, amount: {}", topup.getTenantId(), topup.getId(), topup.getApprovedAmount());
    }

    @Override
    public void topupRejected(TopupRequest topup) {
        log.info("Top-up rejected - tenantId: {}, topup: {}", topup.getTenantId(), topup.getId());
    }

    @Override
    public void applicationMoreInfo(AgencyApplication application, String resumePath) {
        log.info("Application needs more information - application: {}", application.getId());
    }

    @Override
    public void applicationRejected(AgencyApplication application) {
        log.info("Application rejected - application: {}", application.getId());
    }

    @Override
    public void applicationApproved(AgencyApplication application) {
        log.info("Application approved - application: {}, tenantId: {}", application.getId(), application.getTenantId());
    }

    @Override
    public void bookingTicketed(PlatformBooking booking) {
        log.info("Booking ticketed - booking: {}, tenantId: {}", booking.getId(), booking.getTenantId());
    }

    @Override
    public void bookingFailed(PlatformBooking booking) {
        log.info("Booking failed - booking: {}, tenantId: {}", booking.getId(), booking.getTenantId());
    }

    @Override
    public void bookingCancelled(PlatformBooking booking) {
        log.info("Booking cancelled - booking: {}, tenantId: {}", booking.getId(), booking.getTenantId());
    }
}
