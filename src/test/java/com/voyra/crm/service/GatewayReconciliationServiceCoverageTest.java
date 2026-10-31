package com.voyra.crm.service;

import com.voyra.crm.entity.GatewayOrder;
import com.voyra.crm.entity.TopupRequest;
import com.voyra.crm.enums.GatewayOrderPurpose;
import com.voyra.crm.enums.GatewayOrderStatus;
import com.voyra.crm.enums.TopupMode;
import com.voyra.crm.enums.TopupStatus;
import com.voyra.crm.repository.GatewayOrderRepository;
import com.voyra.crm.repository.TopupRequestRepository;
import com.voyra.crm.service.payments.PaymentGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The reconciler leaves alone what a webhook or admin already settled, and expires only what it may. */
@ExtendWith(MockitoExtension.class)
class GatewayReconciliationServiceCoverageTest {

    @Mock private TopupRequestRepository topupRepository;
    @Mock private GatewayOrderRepository orderRepository;
    @Mock private GatewayWebhookService webhookService;
    @Mock private PaymentGateway gateway;
    @Mock private PlatformTransactionManager transactionManager;
    private GatewayReconciliationService service;

    @BeforeEach
    void setUp() {
        service = new GatewayReconciliationService(topupRepository, orderRepository, webhookService, gateway, transactionManager);
    }

    private void due(TopupRequest... topups) {
        when(topupRepository.findByStatusAndModeAndCreatedAtBefore(eq(TopupStatus.PENDING), eq(TopupMode.GATEWAY), any()))
                .thenReturn(List.of(topups));
    }

    private void subscriptionOrders(GatewayOrder... orders) {
        when(orderRepository.findByPurposeAndStatusAndCreatedAtBefore(eq(GatewayOrderPurpose.SUBSCRIPTION), eq(GatewayOrderStatus.CREATED), any()))
                .thenReturn(List.of(orders));
    }

    @Test
    void topUpsAlreadySettledOrWithoutAnOrderAreSkipped() {
        TopupRequest gone = TopupRequest.builder().id("A").build();
        TopupRequest credited = TopupRequest.builder().id("B").status(TopupStatus.APPROVED).build();
        TopupRequest noOrder = TopupRequest.builder().id("C").status(TopupStatus.PENDING).gatewayOrderId("order_C").build();
        due(gone, credited, noOrder);
        subscriptionOrders();
        when(topupRepository.findByIdForUpdate("A")).thenReturn(Optional.empty());
        when(topupRepository.findByIdForUpdate("B")).thenReturn(Optional.of(credited));
        when(topupRepository.findByIdForUpdate("C")).thenReturn(Optional.of(noOrder));
        when(orderRepository.findByGatewayOrderIdForUpdate("order_C")).thenReturn(Optional.empty());

        service.reconcile();

        verifyNoInteractions(gateway, webhookService);
        assertThat(noOrder.getStatus()).isEqualTo(TopupStatus.PENDING);
    }

    @Test
    void aStaleTopUpWhoseOrderIsNoLongerOpenExpiresWithoutTouchingTheOrderStatus() {
        TopupRequest t = TopupRequest.builder().id("D").status(TopupStatus.PENDING).gatewayOrderId("order_D")
                .createdAt(Instant.now().minus(Duration.ofHours(30))).build();
        GatewayOrder o = GatewayOrder.builder().gatewayOrderId("order_D").status(GatewayOrderStatus.FAILED).build();
        due(t);
        subscriptionOrders();
        when(topupRepository.findByIdForUpdate("D")).thenReturn(Optional.of(t));
        when(orderRepository.findByGatewayOrderIdForUpdate("order_D")).thenReturn(Optional.of(o));

        service.reconcile();

        assertThat(t.getStatus()).isEqualTo(TopupStatus.EXPIRED);
        assertThat(o.getStatus()).isEqualTo(GatewayOrderStatus.FAILED);
        assertThat(o.getUpdatedAt()).isNotNull();
        verifyNoInteractions(gateway);
    }

    @Test
    void subscriptionOrdersAlreadyHandledAreSkippedAndAFailureDoesNotStopTheRest() {
        due();
        GatewayOrder missing = GatewayOrder.builder().gatewayOrderId("o1").build();
        GatewayOrder paid = GatewayOrder.builder().gatewayOrderId("o2").build();
        GatewayOrder broken = GatewayOrder.builder().gatewayOrderId("o3").build();
        GatewayOrder open = GatewayOrder.builder().gatewayOrderId("o4").status(GatewayOrderStatus.CREATED)
                .createdAt(Instant.now().minus(Duration.ofHours(2))).build();
        subscriptionOrders(missing, paid, broken, open);
        when(orderRepository.findByGatewayOrderIdForUpdate("o1")).thenReturn(Optional.empty());
        when(orderRepository.findByGatewayOrderIdForUpdate("o2")).thenReturn(Optional.of(
                GatewayOrder.builder().gatewayOrderId("o2").status(GatewayOrderStatus.PAID).build()));
        when(orderRepository.findByGatewayOrderIdForUpdate("o3")).thenThrow(new IllegalStateException("db down"));
        when(orderRepository.findByGatewayOrderIdForUpdate("o4")).thenReturn(Optional.of(open));
        when(gateway.fetchOrderPayments("o4")).thenReturn(List.of());

        service.reconcile();

        verify(gateway).fetchOrderPayments("o4");
        assertThat(open.getStatus()).isEqualTo(GatewayOrderStatus.CREATED);
        verifyNoInteractions(webhookService);
    }
}
