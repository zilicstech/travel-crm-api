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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GatewayReconciliationServiceTest {

    @Mock
    private TopupRequestRepository topupRepository;
    @Mock
    private GatewayOrderRepository orderRepository;
    @Mock
    private GatewayWebhookService webhookService;
    @Mock
    private PaymentGateway gateway;
    @Mock
    private PlatformTransactionManager transactionManager;

    private GatewayReconciliationService service;

    @BeforeEach
    void setUp() {
        service = new GatewayReconciliationService(topupRepository, orderRepository, webhookService, gateway, transactionManager);
    }

    private TopupRequest topup(String id, Duration age) {
        TopupRequest t = TopupRequest.builder().id(id).mode(TopupMode.GATEWAY).status(TopupStatus.PENDING)
                .gatewayOrderId("order_" + id).createdAt(Instant.now().minus(age)).build();
        given(topupRepository.findByIdForUpdate(id)).willReturn(Optional.of(t));
        GatewayOrder o = GatewayOrder.builder().id("G" + id).gatewayOrderId("order_" + id).status(GatewayOrderStatus.CREATED).build();
        given(orderRepository.findByGatewayOrderIdForUpdate("order_" + id)).willReturn(Optional.of(o));
        given(topupRepository.findByStatusAndModeAndCreatedAtBefore(any(), any(), any())).willReturn(List.of(t));
        return t;
    }

    private static GatewayPayment captured(String id) {
        return new GatewayPayment("pay_" + id, "order_" + id, new BigDecimal("100.00"), "INR", "captured");
    }

    @Test
    void pendingOlderThan15MinutesWithACapturedPaymentIsCredited() {
        topup("A", Duration.ofMinutes(20));
        GatewayPayment p = captured("A");
        given(gateway.fetchOrderPayments("order_A")).willReturn(List.of(new GatewayPayment("pay_x", "order_A", BigDecimal.TEN, "INR", "failed"), p));

        service.reconcile();

        verify(webhookService).applyCapture(any(GatewayOrder.class), org.mockito.ArgumentMatchers.eq(p));
    }

    @Test
    void pendingOlderThan24HoursWithNoCaptureExpires() {
        TopupRequest t = topup("B", Duration.ofHours(25));
        given(gateway.fetchOrderPayments("order_B")).willReturn(List.of());

        service.reconcile();

        assertThat(t.getStatus()).isEqualTo(TopupStatus.EXPIRED);
        assertThat(orderRepository.findByGatewayOrderIdForUpdate("order_B").get().getStatus()).isEqualTo(GatewayOrderStatus.EXPIRED);
    }

    @Test
    void pendingBetween15MinutesAnd24HoursWithNoCaptureStaysPending() {
        TopupRequest t = topup("C", Duration.ofHours(2));
        given(gateway.fetchOrderPayments("order_C")).willReturn(List.of());

        service.reconcile();

        assertThat(t.getStatus()).isEqualTo(TopupStatus.PENDING);
    }

    @Test
    void pendingYoungerThan15MinutesIsLeftAlone() {
        // The cutoff query is what excludes it; verify the cutoff is ~15 minutes ago.
        given(topupRepository.findByStatusAndModeAndCreatedAtBefore(any(), any(), any())).willReturn(List.of());

        service.reconcile();

        var cutoff = org.mockito.ArgumentCaptor.forClass(Instant.class);
        verify(topupRepository).findByStatusAndModeAndCreatedAtBefore(org.mockito.ArgumentMatchers.eq(TopupStatus.PENDING),
                org.mockito.ArgumentMatchers.eq(TopupMode.GATEWAY), cutoff.capture());
        assertThat(Duration.between(cutoff.getValue(), Instant.now())).isBetween(Duration.ofMinutes(14), Duration.ofMinutes(16));
        verify(gateway, never()).fetchOrderPayments(any());
    }

    @Test
    void gatewayErrorLeavesTheRowPendingAndContinues() {
        TopupRequest bad = topup("D", Duration.ofHours(30));
        TopupRequest good = topup("E", Duration.ofHours(30));
        given(topupRepository.findByStatusAndModeAndCreatedAtBefore(any(), any(), any())).willReturn(List.of(bad, good));
        given(gateway.fetchOrderPayments("order_D")).willThrow(new IllegalStateException("gateway down"));
        given(gateway.fetchOrderPayments("order_E")).willReturn(List.of());

        service.reconcile();

        assertThat(bad.getStatus()).isEqualTo(TopupStatus.PENDING);
        assertThat(good.getStatus()).isEqualTo(TopupStatus.EXPIRED);
    }
}
