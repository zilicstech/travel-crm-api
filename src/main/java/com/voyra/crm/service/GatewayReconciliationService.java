package com.voyra.crm.service;

import com.voyra.crm.entity.GatewayOrder;
import com.voyra.crm.entity.TopupRequest;
import com.voyra.crm.enums.GatewayOrderStatus;
import com.voyra.crm.enums.TopupMode;
import com.voyra.crm.enums.TopupStatus;
import com.voyra.crm.models.GatewayPayment;
import com.voyra.crm.repository.GatewayOrderRepository;
import com.voyra.crm.repository.TopupRequestRepository;
import com.voyra.crm.service.payments.PaymentGateway;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Catches top-ups whose webhook never arrived: PENDING gateway top-ups older than 15 minutes are checked against the
 * gateway and credited through the webhook's own capture path (same {@code gw:} key, so a late webhook is a no-op);
 * ones unpaid after 24 hours expire. Each top-up runs in its own transaction so one failure rolls back only itself.
 */
@Service
@Slf4j
public class GatewayReconciliationService {

    static final Duration MIN_AGE = Duration.ofMinutes(15);
    static final Duration EXPIRY = Duration.ofHours(24);

    private final TopupRequestRepository topupRepository;
    private final GatewayOrderRepository orderRepository;
    private final GatewayWebhookService webhookService;
    private final PaymentGateway gateway;
    private final TransactionTemplate tx;

    public GatewayReconciliationService(TopupRequestRepository topupRepository, GatewayOrderRepository orderRepository,
                                        GatewayWebhookService webhookService, PaymentGateway gateway,
                                        PlatformTransactionManager transactionManager) {
        this.topupRepository = topupRepository;
        this.orderRepository = orderRepository;
        this.webhookService = webhookService;
        this.gateway = gateway;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void reconcile() {
        List<TopupRequest> due = topupRepository.findByStatusAndModeAndCreatedAtBefore(
                TopupStatus.PENDING, TopupMode.GATEWAY, Instant.now().minus(MIN_AGE));
        for (TopupRequest t : due) {
            try {
                tx.executeWithoutResult(s -> reconcileOne(t.getId()));
            } catch (Exception e) {
                log.error("Gateway reconciliation failed for top-up {}: {}", t.getId(), e.getMessage(), e);
            }
        }
        if (!due.isEmpty()) {
            log.info("Gateway reconciliation checked {} pending top-up(s)", due.size());
        }
    }

    private void reconcileOne(String topupId) {
        TopupRequest topup = topupRepository.findByIdForUpdate(topupId).orElse(null);
        if (topup == null || topup.getStatus() != TopupStatus.PENDING) {
            return;  // a webhook or an admin got there first
        }
        GatewayOrder order = orderRepository.findByGatewayOrderIdForUpdate(topup.getGatewayOrderId()).orElse(null);
        if (order == null) {
            return;
        }
        if (order.getStatus() == GatewayOrderStatus.CREATED) {
            GatewayPayment paid = gateway.fetchOrderPayments(order.getGatewayOrderId()).stream()
                    .filter(p -> "captured".equals(p.status())).findFirst().orElse(null);
            if (paid != null) {
                webhookService.applyCapture(order, paid);
                return;
            }
        }
        if (topup.getCreatedAt().isBefore(Instant.now().minus(EXPIRY))) {
            topup.setStatus(TopupStatus.EXPIRED);
            if (order.getStatus() == GatewayOrderStatus.CREATED) {
                order.setStatus(GatewayOrderStatus.EXPIRED);
            }
            order.setUpdatedAt(Instant.now());
        }
    }
}
