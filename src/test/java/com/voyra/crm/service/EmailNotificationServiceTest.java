package com.voyra.crm.service;

import com.voyra.crm.entity.Agent;
import com.voyra.crm.entity.AgencyApplication;
import com.voyra.crm.entity.PlatformAdmin;
import com.voyra.crm.entity.PlatformBooking;
import com.voyra.crm.entity.SubscriptionInvoice;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.entity.TopupRequest;
import com.voyra.crm.enums.NotificationKind;
import com.voyra.crm.enums.PlatformBookingStatus;
import com.voyra.crm.enums.PricingProduct;
import com.voyra.crm.repository.AgentRepository;
import com.voyra.crm.repository.PlatformAdminRepository;
import com.voyra.crm.repository.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EmailNotificationServiceTest {

    private static final String BASE = "https://app.voyra.test";

    @Mock
    private TenantRepository tenantRepository;
    @Mock
    private PlatformAdminRepository platformAdminRepository;
    @Mock
    private AgentRepository agentRepository;
    @Mock
    private NotificationOutboxService outbox;

    private EmailNotificationService service;

    @BeforeEach
    void setUp() {
        service = new EmailNotificationService(tenantRepository, platformAdminRepository, agentRepository, outbox, BASE + "/");
        given(tenantRepository.findById("T1")).willReturn(Optional.of(Tenant.builder().id("T1").agencyName("Sky Tours")
                .ownerName("Asha").ownerEmail("owner@sky.test").build()));
    }

    @Test
    void lowBalanceGoesToTheAgencyOwner() {
        service.lowBalance("T1", new BigDecimal("900.00"));

        NotificationTemplates.Email email = queued(NotificationKind.LOW_BALANCE, "owner@sky.test");
        assertThat(email.text()).contains("Sky Tours").contains("₹900.00").contains(BASE + "/agency/wallet");
    }

    @Test
    void platformAlertsGoToEveryActivePlatformAdmin() {
        given(platformAdminRepository.findByIsActiveTrue()).willReturn(List.of(
                PlatformAdmin.builder().id("A1").email("root@voyra.test").build(),
                PlatformAdmin.builder().id("A2").email("ops@voyra.test").build()));

        service.platformAlert("Late ticket", "Booking PB1 ticketed late");

        queued(NotificationKind.PLATFORM_ALERT, "root@voyra.test", "ops@voyra.test");
    }

    @Test
    void subscriptionNoticesGoToTheOwner() {
        service.subscriptionExpiring("T1", "TRIAL", Instant.now().plusSeconds(86_400), 1);
        queued(NotificationKind.SUBSCRIPTION_EXPIRING, "owner@sky.test");

        service.subscriptionInvoiceIssued(SubscriptionInvoice.builder().tenantId("T1").number("VOY-SUB-2026-0001")
                .total(new BigDecimal("2948.82")).dueAt(Instant.now()).build());
        assertThat(queued(NotificationKind.SUBSCRIPTION_INVOICE, "owner@sky.test").text()).contains("VOY-SUB-2026-0001");
    }

    @Test
    void topupDecisionsGoToTheOwner() {
        TopupRequest t = TopupRequest.builder().id("R1").tenantId("T1").amount(new BigDecimal("50000.00"))
                .approvedAmount(new BigDecimal("49500.00")).reviewNote("UTR not in statement").build();

        service.topupApproved(t);
        assertThat(queued(NotificationKind.TOPUP_APPROVED, "owner@sky.test").text()).contains("₹49,500.00");

        service.topupRejected(t);
        assertThat(queued(NotificationKind.TOPUP_REJECTED, "owner@sky.test").text()).contains("UTR not in statement");
    }

    @Test
    void applicationDecisionsGoToTheApplicantWithAnAbsoluteResumeLink() {
        AgencyApplication app = AgencyApplication.builder().id("app-1").agencyName("Sky Tours").ownerName("Asha")
                .ownerEmail("asha@sky.test").reviewNote("Upload the GST certificate").rejectionReason("PAN unreadable").build();

        service.applicationMoreInfo(app, "/apply?application=app-1&token=tok");
        assertThat(queued(NotificationKind.APPLICATION_MORE_INFO, "asha@sky.test").text())
                .contains(BASE + "/apply?application=app-1&token=tok").contains("Upload the GST certificate");

        service.applicationRejected(app);
        assertThat(queued(NotificationKind.APPLICATION_REJECTED, "asha@sky.test").text()).contains("PAN unreadable");

        service.applicationApproved(app);
        assertThat(queued(NotificationKind.APPLICATION_APPROVED, "asha@sky.test").text()).contains(BASE + "/login");
    }

    @Test
    void bookingEmailsGoToTheAgentWhoBookedIt() {
        given(agentRepository.findById("AG1")).willReturn(Optional.of(Agent.builder().id("AG1").tenantId("T1")
                .email("ravi@sky.test").isActive(true).build()));

        service.bookingTicketed(booking("AG1", PlatformBookingStatus.TICKETED));

        assertThat(queued(NotificationKind.BOOKING_TICKETED, "ravi@sky.test").text())
                .contains("PNR123").contains(BASE + "/agents/platform-bookings/PB1");
    }

    @Test
    void bookingEmailsGoToTheOwnerWhenTheOwnerBookedIt() {
        service.bookingFailed(booking("T1", PlatformBookingStatus.FAILED));

        assertThat(queued(NotificationKind.BOOKING_FAILED, "owner@sky.test").text()).contains(BASE + "/agency/platform-bookings/PB1");
    }

    /** An ex-employee (deactivated) or an agent of another agency never gets the email; the owner does. */
    @Test
    void bookingEmailsFallBackToTheOwnerForAnInactiveOrForeignAgent() {
        given(agentRepository.findById("AG1")).willReturn(Optional.of(Agent.builder().id("AG1").tenantId("T1")
                .email("ravi@sky.test").isActive(false).build()));
        given(agentRepository.findById("AG2")).willReturn(Optional.of(Agent.builder().id("AG2").tenantId("T2")
                .email("other@else.test").isActive(true).build()));

        service.bookingCancelled(booking("AG1", PlatformBookingStatus.CANCELLED));
        queued(NotificationKind.BOOKING_CANCELLED, "owner@sky.test");

        service.bookingCancelled(booking("AG2", PlatformBookingStatus.CANCELLED));
        queued(NotificationKind.BOOKING_CANCELLED, "owner@sky.test");
    }

    @Test
    void anUnknownAgencyQueuesNothingAndNeverThrows() {
        assertThatCode(() -> service.lowBalance("NOPE", BigDecimal.ONE)).doesNotThrowAnyException();
        verifyNoInteractions(outbox);
    }

    @Test
    void aFailureInsideNeverReachesTheBusinessTransaction() {
        doThrow(new IllegalStateException("boom")).when(outbox).enqueue(any(), anyCollection(), any());

        assertThatCode(() -> service.lowBalance("T1", BigDecimal.ONE)).doesNotThrowAnyException();
        given(tenantRepository.findById("T1")).willThrow(new IllegalStateException("db down"));
        assertThatCode(() -> service.topupApproved(TopupRequest.builder().id("R1").tenantId("T1").build())).doesNotThrowAnyException();
    }

    private static PlatformBooking booking(String bookedBy, PlatformBookingStatus status) {
        return PlatformBooking.builder().id("PB1").tenantId("T1").product(PricingProduct.FLIGHT).status(status)
                .bookedBy(bookedBy).pnr("PNR123").displayed(new BigDecimal("4500.00")).cancelRefund(new BigDecimal("4000.00")).build();
    }

    /** Asserts the latest enqueue of {@code kind} went to exactly these recipients; returns its email. */
    @SuppressWarnings("unchecked")
    private NotificationTemplates.Email queued(NotificationKind kind, String... recipients) {
        ArgumentCaptor<Collection<String>> to = ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<NotificationTemplates.Email> email = ArgumentCaptor.forClass(NotificationTemplates.Email.class);
        verify(outbox, org.mockito.Mockito.atLeastOnce()).enqueue(eq(kind), to.capture(), email.capture());
        assertThat(to.getValue()).containsExactly(recipients);
        return email.getValue();
    }
}
