package com.voyra.crm.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyra.crm.entity.GatewayOrder;
import com.voyra.crm.entity.GatewayWebhookOrphan;
import com.voyra.crm.entity.TopupRequest;
import com.voyra.crm.entity.WalletEntry;
import com.voyra.crm.enums.GatewayOrderPurpose;
import com.voyra.crm.enums.GatewayOrderStatus;
import com.voyra.crm.enums.ReceivableReason;
import com.voyra.crm.enums.TopupStatus;
import com.voyra.crm.enums.WalletStatus;
import com.voyra.crm.enums.WalletEntryType;
import com.voyra.crm.models.GatewayPayment;
import com.voyra.crm.repository.GatewayOrderRepository;
import com.voyra.crm.repository.GatewayWebhookOrphanRepository;
import com.voyra.crm.repository.TopupRequestRepository;
import com.voyra.crm.repository.WalletRepository;
import com.voyra.crm.service.payments.PaymentGateway;
import com.voyra.crm.util.UniqueIdResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one gateway webhook handler, routed by gateway_order.purpose. Order row locked FOR UPDATE so redeliveries
 * serialise; money moves only through WalletService with the payment / refund id as the idempotency key.
 */
@Service
public class GatewayWebhookService {

    public enum WebhookOutcome { PROCESSED, IGNORED, RETRY }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String REF_TYPE = "TOPUP_REQUEST";

    private final GatewayOrderRepository orderRepository;
    private final GatewayWebhookOrphanRepository orphanRepository;
    private final TopupRequestRepository topupRepository;
    private final WalletRepository walletRepository;
    private final WalletService walletService;
    private final NotificationService notificationService;
    private final PaymentGateway gateway;
    private final SubscriptionBillingService billingService;
    private final int orphanMaxAttempts;

    public GatewayWebhookService(GatewayOrderRepository orderRepository, GatewayWebhookOrphanRepository orphanRepository,
                                 TopupRequestRepository topupRepository, WalletRepository walletRepository, WalletService walletService,
                                 NotificationService notificationService, PaymentGateway gateway,
                                 SubscriptionBillingService billingService,
                                 @Value("${app.payments.orphan-max-attempts:5}") int orphanMaxAttempts) {
        this.orderRepository = orderRepository;
        this.orphanRepository = orphanRepository;
        this.topupRepository = topupRepository;
        this.walletRepository = walletRepository;
        this.walletService = walletService;
        this.notificationService = notificationService;
        this.gateway = gateway;
        this.billingService = billingService;
        this.orphanMaxAttempts = orphanMaxAttempts;
    }

    /** Bad signature or unreadable body: IllegalArgumentException (400), nothing stored. */
    @Transactional
    public WebhookOutcome handle(String rawBody, String signature) {
        if (!gateway.verifyWebhook(rawBody, signature)) {
            throw new IllegalArgumentException("Invalid webhook signature");
        }
        JsonNode root;
        try {
            root = JSON.readTree(rawBody);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Unreadable webhook body");
        }
        String event = root.path("event").asText();
        JsonNode payload = root.path("payload");
        JsonNode payment = payload.path("payment").path("entity");
        String orderId = payment.path("order_id").asText(payload.path("order").path("entity").path("id").asText());
        if (event.isEmpty() || orderId.isEmpty()) {
            return WebhookOutcome.IGNORED;
        }
        // refund / dispute events name their own id and amount; payment events use the payment's.
        JsonNode subject = payload.has("refund") ? payload.path("refund").path("entity")
                : payload.has("dispute") ? payload.path("dispute").path("entity") : payment;
        String subjectId = subject.path("id").asText();

        GatewayOrder order = orderRepository.findByGatewayOrderIdForUpdate(orderId).orElse(null);
        if (order == null) {
            return orphan(orderId, event, subjectId, subject);
        }
        List<Map<String, Object>> events = new ArrayList<>(order.getEvents());
        events.add(Map.of("id", subjectId, "event", event, "at", Instant.now().toString()));
        order.setEvents(events);
        order.setUpdatedAt(Instant.now());

        return switch (event) {
            case "payment.captured", "order.paid" -> captured(order, payment);
            case "refund.processed", "payment.dispute.created" ->
                    reversed(order, subjectId, paise(subject), subject.path("payment_id").asText(payment.path("id").asText()));
            // payment.failed is not final: the customer can retry on the same order, and the 24 h expiry handles abandonment.
            default -> WebhookOutcome.IGNORED;
        };
    }

    private WebhookOutcome captured(GatewayOrder order, JsonNode payment) {
        return applyCapture(order, new GatewayPayment(payment.path("id").asText(), order.getGatewayOrderId(), paise(payment),
                payment.path("currency").asText(), payment.path("status").asText()));
    }

    /** The one capture path, shared with the reconciliation job. Caller holds the order row lock inside a transaction. */
    public WebhookOutcome applyCapture(GatewayOrder order, GatewayPayment payment) {
        String paymentId = payment.paymentId();
        BigDecimal amount = payment.amountInr();
        String currency = payment.currency();
        String status = payment.status();
        boolean matches = amount.compareTo(order.getAmount()) == 0 && "INR".equals(currency) && "INR".equals(order.getCurrency())
                && "captured".equals(status);
        if (order.getStatus() == GatewayOrderStatus.FLAGGED) {
            // Customer paid and nothing is credited. Alert once per payment: order.paid repeats payment.captured.
            if (matches && order.getEvents().stream().filter(e -> paymentId.equals(e.get("id"))).count() <= 1) {
                notificationService.platformAlert("Captured payment on a flagged order",
                        "Order " + order.getGatewayOrderId() + " is flagged; payment " + paymentId + " (INR " + amount.toPlainString()
                                + ") was captured and not credited - refund it or credit it by hand.");
            }
            return WebhookOutcome.IGNORED;
        }
        if (!matches) {
            order.setStatus(GatewayOrderStatus.FLAGGED);
            notificationService.platformAlert("Gateway payment does not match its order",
                    "Order " + order.getGatewayOrderId() + " expected a captured INR " + order.getAmount().toPlainString()
                            + ", payment " + paymentId + " was " + status + " " + currency + " " + amount.toPlainString()
                            + ". Not credited.");
            return WebhookOutcome.PROCESSED;
        }
        if (order.getPurpose() == GatewayOrderPurpose.SUBSCRIPTION) {
            // Never a wallet call. Idempotent by payment id: a redelivery finds this order PAID and markPaid a no-op.
            if (!billingService.markPaid(order.getRefId(), paymentId)) {
                // A PAID order is a redelivery. Otherwise the capture was not applied (invoice already paid or void; billing
                // alerted): FLAGGED stops reconciliation re-trying it.
                if (order.getStatus() != GatewayOrderStatus.PAID) {
                    order.setStatus(GatewayOrderStatus.FLAGGED);
                }
                return WebhookOutcome.IGNORED;
            }
            order.setStatus(GatewayOrderStatus.PAID);
            return WebhookOutcome.PROCESSED;
        }
        TopupRequest topup = topup(order);
        if (topup.getStatus() == TopupStatus.APPROVED) {
            if (!paymentId.equals(topup.getGatewayPaymentId())) {
                notificationService.platformAlert("Second captured payment on a credited top-up",
                        "Order " + order.getGatewayOrderId() + " already credited by " + topup.getGatewayPaymentId()
                                + "; payment " + paymentId + " was not credited - refund it.");
            }
            return WebhookOutcome.IGNORED;
        }
        // Checked here, not left to credit()'s throw: a rollback would make the gateway retry a dead wallet forever.
        boolean open = walletRepository.findById(topup.getWalletId()).map(w -> w.getStatus() == WalletStatus.OPEN).orElse(false);
        if (!open) {
            order.setStatus(GatewayOrderStatus.FLAGGED);
            notificationService.platformAlert("Gateway payment for a closed wallet",
                    "Order " + order.getGatewayOrderId() + ", payment " + paymentId + " (INR " + amount.toPlainString()
                            + ") was captured but agency " + topup.getTenantId()
                            + "'s wallet is not open. Not credited - refund it offline.");
            return WebhookOutcome.PROCESSED;
        }
        WalletEntry entry = walletService.credit(topup.getTenantId(), WalletEntryType.TOPUP, amount, "gw:" + paymentId,
                REF_TYPE, topup.getId(), "Online top-up, payment " + paymentId);
        topup.setStatus(TopupStatus.APPROVED);
        topup.setApprovedAmount(amount);
        topup.setGatewayPaymentId(paymentId);
        topup.setCreditEntryId(entry.getId());
        order.setStatus(GatewayOrderStatus.PAID);
        return WebhookOutcome.PROCESSED;
    }

    private WebhookOutcome reversed(GatewayOrder order, String reversalId, BigDecimal amount, String paymentId) {
        if (order.getPurpose() != GatewayOrderPurpose.TOPUP || amount.signum() <= 0) {
            return WebhookOutcome.IGNORED;
        }
        // A FLAGGED order was never credited and is refunded offline; its reversal must not retry forever.
        if (order.getStatus() == GatewayOrderStatus.FLAGGED) {
            return WebhookOutcome.IGNORED;
        }
        TopupRequest topup = topup(order);
        if (topup.getCreditEntryId() == null) {
            return WebhookOutcome.RETRY;  // arrived before its capture; the redelivery finds it credited
        }
        if (!paymentId.equals(topup.getGatewayPaymentId())) {
            notificationService.platformAlert("Gateway reversal for an uncredited payment",
                    "Order " + order.getGatewayOrderId() + " was credited by " + topup.getGatewayPaymentId() + "; " + reversalId
                            + " is a refund/dispute for a payment that was never credited (" + paymentId + ") - handle offline.");
            return WebhookOutcome.IGNORED;
        }
        // A refund and a dispute on one payment take back at most what was credited.
        BigDecimal remaining = topup.getApprovedAmount().subtract(walletService.chargebacksPosted(REF_TYPE, topup.getId()));
        BigDecimal take = amount.min(remaining);
        if (take.signum() <= 0) {
            return WebhookOutcome.IGNORED;
        }
        walletService.debitOrReceivable(topup.getTenantId(), WalletEntryType.ADJUSTMENT, take, "gw-refund:" + reversalId,
                REF_TYPE, topup.getId(), ReceivableReason.CHARGEBACK);
        return WebhookOutcome.PROCESSED;
    }

    private WebhookOutcome orphan(String orderId, String event, String subjectId, JsonNode subject) {
        Instant now = Instant.now();
        GatewayWebhookOrphan orphan = orphanRepository.findByGatewayOrderIdAndEvent(orderId, event)
                .orElseGet(() -> GatewayWebhookOrphan.builder().id(UniqueIdResolver.resolve(orphanRepository::existsById))
                        .gatewayOrderId(orderId).event(event).firstSeenAt(now).build());
        Map<String, Object> summary = new LinkedHashMap<>();  // ids and amount only - no card or contact data
        summary.put("id", subjectId);
        summary.put("amountPaise", subject.path("amount").asLong());
        summary.put("currency", subject.path("currency").asText(null));
        orphan.setPayload(summary);
        orphan.setAttempts(orphan.getAttempts() + 1);
        orphan.setLastSeenAt(now);
        orphanRepository.save(orphan);
        if (orphan.getAttempts() < orphanMaxAttempts) {
            return WebhookOutcome.RETRY;
        }
        if (orphan.getAttempts() == orphanMaxAttempts) {
            notificationService.platformAlert("Gateway webhook for an unknown order",
                    event + " for order " + orderId + " (" + subjectId + ") arrived " + orphanMaxAttempts
                            + " times with no matching gateway_order. Nothing was credited.");
        }
        return WebhookOutcome.IGNORED;
    }

    private TopupRequest topup(GatewayOrder order) {
        return topupRepository.findById(order.getRefId())
                .orElseThrow(() -> new IllegalStateException("Gateway order " + order.getGatewayOrderId() + " has no top-up"));
    }

    private static BigDecimal paise(JsonNode entity) {
        return BigDecimal.valueOf(entity.path("amount").asLong(), 2);
    }
}
