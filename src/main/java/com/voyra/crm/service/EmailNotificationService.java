package com.voyra.crm.service;

import com.voyra.crm.config.SmtpConfigured;
import com.voyra.crm.entity.AgencyApplication;
import com.voyra.crm.entity.PlatformAdmin;
import com.voyra.crm.entity.PlatformBooking;
import com.voyra.crm.entity.SubscriptionInvoice;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.entity.TopupRequest;
import com.voyra.crm.enums.NotificationKind;
import com.voyra.crm.enums.PricingProduct;
import com.voyra.crm.repository.AgentRepository;
import com.voyra.crm.repository.PlatformAdminRepository;
import com.voyra.crm.repository.TenantRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Emails through the outbox (SMTP_HOST set). Recipients are resolved and the email rendered in the caller's transaction; the row is
 * written only after it commits. Nothing here ever throws into the caller: a notice that cannot be built is logged and dropped.
 */
@Slf4j
@Service
@Conditional(SmtpConfigured.class)
public class EmailNotificationService implements NotificationService {

    private final TenantRepository tenantRepository;
    private final PlatformAdminRepository platformAdminRepository;
    private final AgentRepository agentRepository;
    private final NotificationOutboxService outbox;
    private final String appBaseUrl;

    public EmailNotificationService(TenantRepository tenantRepository, PlatformAdminRepository platformAdminRepository,
                                    AgentRepository agentRepository, NotificationOutboxService outbox,
                                    @Value("${app.mail.app-base-url:http://localhost:3000}") String appBaseUrl) {
        this.tenantRepository = tenantRepository;
        this.platformAdminRepository = platformAdminRepository;
        this.agentRepository = agentRepository;
        this.outbox = outbox;
        this.appBaseUrl = appBaseUrl.replaceAll("/+$", "");
        log.info("Email notifications are on (SMTP); links point at {}", this.appBaseUrl);
    }

    @Override
    public void lowBalance(String tenantId, BigDecimal available) {
        toOwner(NotificationKind.LOW_BALANCE, tenantId,
                t -> NotificationTemplates.lowBalance(t.getAgencyName(), available, url("/agency/wallet")));
    }

    @Override
    public void platformAlert(String subject, String body) {
        send(NotificationKind.PLATFORM_ALERT, () -> new Queued(
                platformAdminRepository.findByIsActiveTrue().stream().map(PlatformAdmin::getEmail).toList(),
                NotificationTemplates.platformAlert(subject, body)));
    }

    @Override
    public void subscriptionExpiring(String tenantId, String kind, Instant endsAt, int daysLeft) {
        toOwner(NotificationKind.SUBSCRIPTION_EXPIRING, tenantId,
                t -> NotificationTemplates.subscriptionExpiring(t.getAgencyName(), kind, endsAt, daysLeft, url("/agency/subscription")));
    }

    @Override
    public void subscriptionInvoiceIssued(SubscriptionInvoice invoice) {
        toOwner(NotificationKind.SUBSCRIPTION_INVOICE, invoice.getTenantId(),
                t -> NotificationTemplates.subscriptionInvoice(t.getAgencyName(), invoice.getNumber(), invoice.getTotal(),
                        invoice.getDueAt(), url("/agency/subscription")));
    }

    @Override
    public void topupApproved(TopupRequest topup) {
        BigDecimal credited = topup.getApprovedAmount() != null ? topup.getApprovedAmount() : topup.getAmount();
        toOwner(NotificationKind.TOPUP_APPROVED, topup.getTenantId(),
                t -> NotificationTemplates.topupApproved(t.getAgencyName(), credited, url("/agency/wallet")));
    }

    @Override
    public void topupRejected(TopupRequest topup) {
        toOwner(NotificationKind.TOPUP_REJECTED, topup.getTenantId(),
                t -> NotificationTemplates.topupRejected(t.getAgencyName(), topup.getAmount(), topup.getReviewNote(), url("/agency/wallet")));
    }

    @Override
    public void applicationMoreInfo(AgencyApplication application, String resumePath) {
        send(NotificationKind.APPLICATION_MORE_INFO, () -> new Queued(List.of(application.getOwnerEmail()),
                NotificationTemplates.applicationMoreInfo(application.getOwnerName(), application.getAgencyName(),
                        application.getReviewNote(), url(resumePath))));
    }

    @Override
    public void applicationRejected(AgencyApplication application) {
        send(NotificationKind.APPLICATION_REJECTED, () -> new Queued(List.of(application.getOwnerEmail()),
                NotificationTemplates.applicationRejected(application.getOwnerName(), application.getAgencyName(),
                        application.getRejectionReason())));
    }

    @Override
    public void applicationApproved(AgencyApplication application) {
        send(NotificationKind.APPLICATION_APPROVED, () -> new Queued(List.of(application.getOwnerEmail()),
                NotificationTemplates.applicationApproved(application.getOwnerName(), application.getAgencyName(),
                        application.getOwnerEmail(), url("/login"))));
    }

    @Override
    public void bookingTicketed(PlatformBooking b) {
        toBooker(NotificationKind.BOOKING_TICKETED, b,
                link -> NotificationTemplates.bookingTicketed(product(b), b.getId(), b.getPnr(), b.getDisplayed(), link));
    }

    @Override
    public void bookingFailed(PlatformBooking b) {
        toBooker(NotificationKind.BOOKING_FAILED, b, link -> NotificationTemplates.bookingFailed(product(b), b.getId(), link));
    }

    @Override
    public void bookingCancelled(PlatformBooking b) {
        toBooker(NotificationKind.BOOKING_CANCELLED, b,
                link -> NotificationTemplates.bookingCancelled(product(b), b.getId(), b.getPnr(), b.getCancelRefund(), link));
    }

    // ---- recipients

    private record Queued(List<String> recipients, NotificationTemplates.Email email) {
    }

    private void toOwner(NotificationKind kind, String tenantId, Function<Tenant, NotificationTemplates.Email> render) {
        send(kind, () -> tenantRepository.findById(tenantId)
                .map(t -> new Queued(List.of(t.getOwnerEmail()), render.apply(t)))
                .orElse(null));
    }

    /** The agent who booked it while still an active login of this agency; otherwise (owner, or a platform admin acting) the owner. */
    private void toBooker(NotificationKind kind, PlatformBooking b, Function<String, NotificationTemplates.Email> render) {
        send(kind, () -> {
            Optional<String> agentEmail = Optional.ofNullable(b.getBookedBy()).filter(by -> !by.equals(b.getTenantId()))
                    .flatMap(agentRepository::findById)
                    .filter(a -> b.getTenantId().equals(a.getTenantId()) && Boolean.TRUE.equals(a.getIsActive()))
                    .map(a -> a.getEmail());
            if (agentEmail.isPresent()) {
                return new Queued(List.of(agentEmail.get()), render.apply(url("/agents/platform-bookings/" + b.getId())));
            }
            return tenantRepository.findById(b.getTenantId())
                    .map(t -> new Queued(List.of(t.getOwnerEmail()), render.apply(url("/agency/platform-bookings/" + b.getId()))))
                    .orElse(null);
        });
    }

    private void send(NotificationKind kind, Supplier<Queued> build) {
        try {
            Queued q = build.get();
            if (q == null) {
                log.warn("No recipient for a {} notification - nothing queued", kind);
                return;
            }
            outbox.enqueue(kind, q.recipients(), q.email());
        } catch (RuntimeException e) {
            log.error("{} notification not queued: {}", kind, e.getClass().getSimpleName());
        }
    }

    private String url(String path) {
        return appBaseUrl + (path.startsWith("/") ? path : "/" + path);
    }

    private static String product(PlatformBooking b) {
        return b.getProduct() == PricingProduct.HOTEL ? "hotel" : "flight";
    }
}
