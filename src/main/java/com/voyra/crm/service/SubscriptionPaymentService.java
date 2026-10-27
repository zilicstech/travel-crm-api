package com.voyra.crm.service;

import com.voyra.crm.dto.SubscriptionInvoiceResponse;
import com.voyra.crm.dto.SubscriptionPaymentResponse;
import com.voyra.crm.entity.GatewayOrder;
import com.voyra.crm.entity.SubscriptionInvoice;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.enums.GatewayOrderPurpose;
import com.voyra.crm.enums.GatewayOrderStatus;
import com.voyra.crm.exception.NotFoundException;
import com.voyra.crm.models.GatewayOrderCreated;
import com.voyra.crm.repository.GatewayOrderRepository;
import com.voyra.crm.repository.SubscriptionInvoiceRepository;
import com.voyra.crm.repository.TenantRepository;
import com.voyra.crm.repository.WalletRepository;
import com.voyra.crm.service.payments.PaymentGateway;
import com.voyra.crm.service.payments.RazorpayPaymentGateway;
import com.voyra.crm.util.UniqueIdResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;

/**
 * The agency pays a subscription invoice online: a {@code gateway_order(purpose SUBSCRIPTION, ref_id = invoiceId)} for
 * the hosted checkout; only the signed webhook marks it paid. Kept apart from SubscriptionBillingService because the
 * simulate path calls the webhook handler, which itself calls billing.
 */
@Service
@RequiredArgsConstructor
public class SubscriptionPaymentService {

    private final SubscriptionInvoiceRepository invoiceRepository;
    private final GatewayOrderRepository orderRepository;
    private final WalletRepository walletRepository;
    private final TenantRepository tenantRepository;
    private final PaymentGateway gateway;
    private final GatewayWebhookService webhookService;

    /** Reuses the invoice's open order, so a second click never creates a second order. */
    @Transactional
    public SubscriptionPaymentResponse pay(String tenantId, String invoiceId) {
        SubscriptionInvoice inv = invoiceRepository.findByIdForUpdate(invoiceId).filter(i -> i.getTenantId().equals(tenantId))
                .orElseThrow(() -> new NotFoundException("Invoice not found"));
        if (!SubscriptionBillingService.ISSUED.equals(inv.getStatus())) {
            throw new IllegalStateException("This invoice is already " + inv.getStatus().toLowerCase(Locale.ROOT));
        }
        // Same rule as top-ups: prod runs the stub until Razorpay keys exist; only test wallets (which can simulate) may use it.
        if (stub() && !testWallet(tenantId)) {
            throw new IllegalStateException("Online payment is not available yet - pay by bank transfer");
        }
        GatewayOrder order = inv.getGatewayOrderId() == null ? null : orderRepository.findByGatewayOrderId(inv.getGatewayOrderId())
                .filter(o -> o.getStatus() == GatewayOrderStatus.CREATED).orElse(null);
        if (order == null) {
            GatewayOrderCreated created = gateway.createOrder(inv.getTotal(), inv.getId(),
                    Map.of("tenantId", tenantId, "subscriptionInvoiceId", inv.getId()));
            Instant now = Instant.now();
            order = orderRepository.save(GatewayOrder.builder().id(UniqueIdResolver.resolve(orderRepository::existsById))
                    .gatewayOrderId(created.orderId()).purpose(GatewayOrderPurpose.SUBSCRIPTION).refId(inv.getId())
                    .amount(inv.getTotal()).status(GatewayOrderStatus.CREATED).createdAt(now).updatedAt(now).build());
            inv.setGatewayOrderId(created.orderId());
        }
        return SubscriptionPaymentResponse.builder().invoiceId(inv.getId()).gatewayOrderId(order.getGatewayOrderId())
                .keyId(gateway.publicKeyId()).amountPaise(RazorpayPaymentGateway.toPaise(order.getAmount())).provider(gateway.name())
                .agencyName(tenantRepository.findById(tenantId).map(Tenant::getAgencyName).orElse("")).build();
    }

    /**
     * Stub provider with simulation switched on, and test wallets only (404 / 409 otherwise): pays the caller's own invoice by running a
     * payment.captured event through the real webhook handler. Not transactional, so the handler commits before the re-read.
     */
    public SubscriptionInvoiceResponse simulate(String tenantId, String invoiceId) {
        if (!gateway.simulateEnabled()) {
            throw new NotFoundException("Invoice not found");
        }
        SubscriptionInvoice inv = invoiceRepository.findById(invoiceId).filter(i -> i.getTenantId().equals(tenantId))
                .orElseThrow(() -> new NotFoundException("Invoice not found"));
        if (inv.getGatewayOrderId() == null) {
            throw new IllegalStateException("Start the payment first");
        }
        if (!testWallet(tenantId)) {
            throw new IllegalStateException("Simulated payments only work on a test wallet");
        }
        // Fixed payment id per invoice, so a second simulate is a duplicate delivery, not a second payment.
        String body = String.format("{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"pay_stub_%s\","
                        + "\"order_id\":\"%s\",\"amount\":%d,\"currency\":\"INR\",\"status\":\"captured\"}}}}",
                inv.getId(), inv.getGatewayOrderId(), RazorpayPaymentGateway.toPaise(inv.getTotal()));
        webhookService.handle(body, null);
        return SubscriptionBillingService.toResponse(invoiceRepository.findById(invoiceId).orElseThrow());
    }

    private boolean stub() {
        return "stub".equals(gateway.name());
    }

    private boolean testWallet(String tenantId) {
        return walletRepository.findByTenantId(tenantId).map(w -> Boolean.TRUE.equals(w.getIsTest())).orElse(false);
    }
}
