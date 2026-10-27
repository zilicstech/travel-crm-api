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
import com.voyra.crm.repository.NotificationOutboxRepository;
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
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
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

        service.bookingCancelled(booking("AG1", PlatformBookingStatus.CANCELLED), false);
        queued(NotificationKind.BOOKING_CANCELLED, "owner@sky.test");

        service.bookingCancelled(booking("AG2", PlatformBookingStatus.CANCELLED), false);
        queued(NotificationKind.BOOKING_CANCELLED, "owner@sky.test");
    }

    @Test
    void anUnknownAgencyQueuesNothingAndNeverThrows() {
        assertThatCode(() -> service.lowBalance("NOPE", BigDecimal.ONE)).doesNotThrowAnyException();
        assertThat(built(NotificationKind.LOW_BALANCE)).isNull();
    }

    /**
     * Review fix: the owner / admin lookup runs after the commit, so a lookup that fails (a real Spring Data repository marks the
     * surrounding transaction rollback-only when it throws) can never roll back the business transaction.
     */
    @Test
    void aFailingRecipientLookupNeverRollsBackTheBusinessTransaction() {
        FakeTransactionManager txManager = new FakeTransactionManager();
        NotificationOutboxRepository outboxRepository = org.mockito.Mockito.mock(NotificationOutboxRepository.class);
        NotificationOutboxService realOutbox = new NotificationOutboxService(outboxRepository,
                org.mockito.Mockito.mock(org.springframework.mail.javamail.JavaMailSender.class), txManager, "no-reply@voyra.test", 5);
        EmailNotificationService email = new EmailNotificationService(tenantRepository, platformAdminRepository, agentRepository,
                realOutbox, BASE);
        given(tenantRepository.findById("T1")).willAnswer(i -> {
            // what the repository proxy does when it throws inside a transaction: mark it rollback-only
            txManager.rollback(txManager.getTransaction(TransactionDefinition.withDefaults()));
            throw new IllegalStateException("db down");
        });
        java.util.concurrent.atomic.AtomicBoolean committed = new java.util.concurrent.atomic.AtomicBoolean();

        assertThatCode(() -> new TransactionTemplate(txManager).executeWithoutResult(s -> {
            email.lowBalance("T1", BigDecimal.ONE);
            email.topupApproved(TopupRequest.builder().id("R1").tenantId("T1").build());
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    committed.set(true);
                }
            });
        })).doesNotThrowAnyException();

        assertThat(committed).isTrue();
        verify(tenantRepository, org.mockito.Mockito.times(2)).findById("T1"); // looked up, after the commit
        verify(outboxRepository, never()).saveAll(anyList());
    }

    /** Agency-facing emails never carry Voyra's economics or supplier identifiers. */
    @Test
    void agencyEmailsNeverLeakVoyraFiguresOrSupplierIds() {
        given(agentRepository.findById("AG1")).willReturn(Optional.of(Agent.builder().id("AG1").tenantId("T1")
                .email("ravi@sky.test").isActive(true).build()));
        PlatformBooking b = booking("AG1", PlatformBookingStatus.TICKETED).toBuilder()
                .gross(new BigDecimal("5555.55")).base(new BigDecimal("4444.44")).voyraEarned(new BigDecimal("111.11"))
                .voyraEarnedActual(new BigDecimal("121.21")).voyraNet(new BigDecimal("222.22")).agencyCommission(new BigDecimal("33.33"))
                .tds(new BigDecimal("1.67")).supplierBookingId("SUPP-BOOKING-ID").supplierReviewId("SUPP-REVIEW-ID")
                .credentialId("CRED-ID").supplierMessage("RAW SUPPLIER TEXT").build();
        TopupRequest t = TopupRequest.builder().id("R1").tenantId("T1").amount(new BigDecimal("50000.00"))
                .approvedAmount(new BigDecimal("50000.00")).gatewayOrderId("order_SECRET").gatewayPaymentId("pay_SECRET").build();

        service.bookingTicketed(b);
        service.bookingFailed(b);
        service.bookingCancelled(b, false);
        service.lowBalance("T1", new BigDecimal("900.00"));
        service.topupApproved(t);
        service.topupRejected(t);
        service.subscriptionExpiring("T1", "PERIOD", Instant.now(), 7);
        service.subscriptionInvoiceIssued(SubscriptionInvoice.builder().tenantId("T1").number("VOY-SUB-2026-0001")
                .total(new BigDecimal("2948.82")).dueAt(Instant.now()).build());

        for (NotificationKind kind : List.of(NotificationKind.BOOKING_TICKETED, NotificationKind.BOOKING_FAILED,
                NotificationKind.BOOKING_CANCELLED, NotificationKind.LOW_BALANCE, NotificationKind.TOPUP_APPROVED,
                NotificationKind.TOPUP_REJECTED, NotificationKind.SUBSCRIPTION_EXPIRING, NotificationKind.SUBSCRIPTION_INVOICE)) {
            NotificationTemplates.Email e = built(kind).email();
            String all = (e.subject() + "\n" + e.html() + "\n" + e.text()).toLowerCase(java.util.Locale.ROOT);
            assertThat(all).as(kind.name()).doesNotContain("5,555.55", "5555.55", "4,444.44", "111.11", "121.21", "222.22",
                    "33.33", "1.67", "supp-booking-id", "supp-review-id", "cred-id", "raw supplier text", "order_secret", "pay_secret");
            // words checked on what the reader sees (the HTML has CSS like "margin:")
            String words = (e.subject() + "\n" + e.text()).toLowerCase(java.util.Locale.ROOT);
            assertThat(words).as(kind.name()).doesNotContain("gross", "earned", "voyra net", "margin", "commission", "tds");
        }
    }

    @Test
    void aLocalhostLinkBaseWithSmtpOnIsLoggedAsAnError(CapturedOutput output) {
        new EmailNotificationService(tenantRepository, platformAdminRepository, agentRepository, outbox, "http://localhost:3000");

        assertThat(output).contains("ERROR").contains("APP_BASE_URL");
    }

    private static PlatformBooking booking(String bookedBy, PlatformBookingStatus status) {
        return PlatformBooking.builder().id("PB1").tenantId("T1").product(PricingProduct.FLIGHT).status(status)
                .bookedBy(bookedBy).pnr("PNR123").displayed(new BigDecimal("4500.00")).cancelRefund(new BigDecimal("4000.00")).build();
    }

    /** The latest enqueue of {@code kind}, built (after-commit work run here by hand); null when it found no recipient. */
    @SuppressWarnings("unchecked")
    private NotificationOutboxService.Outgoing built(NotificationKind kind) {
        ArgumentCaptor<Supplier<NotificationOutboxService.Outgoing>> build = ArgumentCaptor.forClass(Supplier.class);
        verify(outbox, org.mockito.Mockito.atLeastOnce()).enqueue(eq(kind), build.capture());
        return build.getValue().get();
    }

    /** Asserts the latest enqueue of {@code kind} went to exactly these recipients; returns its email. */
    private NotificationTemplates.Email queued(NotificationKind kind, String... recipients) {
        NotificationOutboxService.Outgoing out = built(kind);
        assertThat(out.recipients()).containsExactly(recipients);
        return out.email();
    }
}
