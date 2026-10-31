package com.voyra.crm.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.voyra.crm.entity.AgencyApplication;
import com.voyra.crm.entity.PlatformBooking;
import com.voyra.crm.entity.SubscriptionInvoice;
import com.voyra.crm.entity.TopupRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Without SMTP every notice is one log line with ids and amounts only - never an email address or a link. */
class LoggingNotificationServiceTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger(LoggingNotificationService.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Level previousLevel;

    // a Spring test context started earlier in the same JVM sets com.voyra.crm to WARN (application-test.properties)
    @BeforeEach
    void capture() {
        previousLevel = logger.getLevel();
        logger.setLevel(Level.INFO);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void release() {
        logger.detachAppender(appender);
        logger.setLevel(previousLevel);
    }

    @Test
    void everyNoticeIsOneLineOfIdsAndAmounts() {
        LoggingNotificationService service = new LoggingNotificationService();
        AgencyApplication application = AgencyApplication.builder().id("APP1").tenantId("T1").build();
        PlatformBooking booking = PlatformBooking.builder().id("PB1").tenantId("T1").build();
        TopupRequest topup = TopupRequest.builder().id("TU1").tenantId("T1").approvedAmount(new BigDecimal("5000")).build();

        service.lowBalance("T1", new BigDecimal("120.50"));
        service.platformAlert("SUPPLIER_MISMATCH", "row PB1");
        service.subscriptionExpiring("T1", "trial", Instant.parse("2026-11-30T00:00:00Z"), 7);
        service.subscriptionInvoiceIssued(SubscriptionInvoice.builder().tenantId("T1").number("VOY-SUB-2026-1").total(new BigDecimal("2948.82")).build());
        service.topupApproved(topup);
        service.topupRejected(topup);
        service.applicationMoreInfo(application, "/apply/resume?token=secret-token");
        service.applicationRejected(application);
        service.applicationApproved(application);
        service.bookingTicketed(booking);
        service.bookingFailed(booking);
        service.bookingCancelled(booking, true);

        assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage).containsExactly(
                "SMTP_HOST is not set - notifications are logged only, no email is sent",
                "Low wallet balance - tenantId: T1, available: 120.50",
                "Platform alert - SUPPLIER_MISMATCH: row PB1",
                "Subscription expiring - tenantId: T1, trial ends 2026-11-30T00:00:00Z (7 day(s) left)",
                "Subscription invoice issued - tenantId: T1, number: VOY-SUB-2026-1, total: 2948.82",
                "Top-up approved - tenantId: T1, topup: TU1, amount: 5000",
                "Top-up rejected - tenantId: T1, topup: TU1",
                "Application needs more information - application: APP1",
                "Application rejected - application: APP1",
                "Application approved - application: APP1, tenantId: T1",
                "Booking ticketed - booking: PB1, tenantId: T1",
                "Booking failed - booking: PB1, tenantId: T1",
                "Booking cancelled - booking: PB1, tenantId: T1");
        assertThat(appender.list).noneMatch(e -> e.getFormattedMessage().contains("secret-token"));
    }
}
