package com.voyra.crm.service;

import com.voyra.crm.dto.SubscriptionPaymentResponse;
import com.voyra.crm.entity.GatewayOrder;
import com.voyra.crm.entity.SubscriptionInvoice;
import com.voyra.crm.entity.Wallet;
import com.voyra.crm.enums.GatewayOrderPurpose;
import com.voyra.crm.enums.GatewayOrderStatus;
import com.voyra.crm.exception.NotFoundException;
import com.voyra.crm.models.GatewayOrderCreated;
import com.voyra.crm.repository.GatewayOrderRepository;
import com.voyra.crm.repository.SubscriptionInvoiceRepository;
import com.voyra.crm.repository.TenantRepository;
import com.voyra.crm.repository.WalletRepository;
import com.voyra.crm.service.payments.PaymentGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class SubscriptionPaymentServiceTest {

    @Mock private SubscriptionInvoiceRepository invoiceRepository;
    @Mock private GatewayOrderRepository orderRepository;
    @Mock private WalletRepository walletRepository;
    @Mock private TenantRepository tenantRepository;
    @Mock private PaymentGateway gateway;
    @Mock private GatewayWebhookService webhookService;
    @InjectMocks private SubscriptionPaymentService service;

    private SubscriptionInvoice inv;

    @BeforeEach
    void setUp() {
        inv = SubscriptionInvoice.builder().id("I1").tenantId("T1").number("VOY-SUB-2026-1").status("ISSUED")
                .total(new BigDecimal("5898.82")).createdAt(Instant.now()).build();
        lenient().when(invoiceRepository.findByIdForUpdate("I1")).thenReturn(Optional.of(inv));
        lenient().when(invoiceRepository.findById("I1")).thenReturn(Optional.of(inv));
        lenient().when(gateway.name()).thenReturn("razorpay");
        lenient().when(gateway.publicKeyId()).thenReturn("rzp_test_1");
        lenient().when(gateway.createOrder(any(), eq("I1"), anyMap())).thenReturn(new GatewayOrderCreated("order_9", 589882L, "INR"));
        lenient().when(orderRepository.save(any(GatewayOrder.class))).thenAnswer(i -> i.getArgument(0));
        lenient().when(tenantRepository.findById("T1")).thenReturn(Optional.empty());
    }

    private void wallet(boolean test) {
        lenient().when(walletRepository.findByTenantId("T1")).thenReturn(Optional.of(Wallet.builder().id("W1").tenantId("T1").isTest(test).build()));
    }

    @Test
    void payCreatesASubscriptionGatewayOrderForTheInvoiceTotal() {
        SubscriptionPaymentResponse r = service.pay("T1", "I1");

        ArgumentCaptor<GatewayOrder> order = ArgumentCaptor.forClass(GatewayOrder.class);
        verify(orderRepository).save(order.capture());
        assertThat(order.getValue().getPurpose()).isEqualTo(GatewayOrderPurpose.SUBSCRIPTION);
        assertThat(order.getValue().getRefId()).isEqualTo("I1");
        assertThat(order.getValue().getAmount()).isEqualByComparingTo("5898.82");
        assertThat(order.getValue().getStatus()).isEqualTo(GatewayOrderStatus.CREATED);
        assertThat(inv.getGatewayOrderId()).isEqualTo("order_9");
        assertThat(r.getGatewayOrderId()).isEqualTo("order_9");
        assertThat(r.getAmountPaise()).isEqualTo(589882L);
        assertThat(r.getKeyId()).isEqualTo("rzp_test_1");
    }

    @Test
    void aSecondPayReusesTheOpenOrder() {
        inv.setGatewayOrderId("order_9");
        given(orderRepository.findByGatewayOrderId("order_9")).willReturn(Optional.of(GatewayOrder.builder().id("G1")
                .gatewayOrderId("order_9").purpose(GatewayOrderPurpose.SUBSCRIPTION).refId("I1")
                .amount(new BigDecimal("5898.82")).status(GatewayOrderStatus.CREATED).build()));

        assertThat(service.pay("T1", "I1").getGatewayOrderId()).isEqualTo("order_9");
        verify(gateway, never()).createOrder(any(), any(), anyMap());
    }

    @Test
    void paidOrOtherAgencysInvoicesCannotBePaid() {
        assertThatThrownBy(() -> service.pay("T2", "I1")).isInstanceOf(NotFoundException.class);
        inv.setStatus("PAID");
        assertThatThrownBy(() -> service.pay("T1", "I1")).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(orderRepository);
    }

    @Test
    void stubProviderPaysOnlyForATestWallet() {
        given(gateway.name()).willReturn("stub");
        wallet(false);
        assertThatThrownBy(() -> service.pay("T1", "I1")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bank transfer");

        wallet(true);
        assertThat(service.pay("T1", "I1").getProvider()).isEqualTo("stub");
    }

    @Test
    void simulateRunsACaptureThroughTheRealWebhookHandler() {
        given(gateway.simulateEnabled()).willReturn(true);
        wallet(true);
        inv.setGatewayOrderId("order_stub_1");

        service.simulate("T1", "I1");

        verify(webhookService).handle(contains("\"order_id\":\"order_stub_1\""), isNull());
        verify(webhookService).handle(contains("\"amount\":589882"), isNull());
    }

    @Test
    void simulateIsStubAndTestWalletOnly() {
        inv.setGatewayOrderId("order_stub_1");
        assertThatThrownBy(() -> service.simulate("T1", "I1")).isInstanceOf(NotFoundException.class);

        given(gateway.simulateEnabled()).willReturn(true);
        wallet(false);
        assertThatThrownBy(() -> service.simulate("T1", "I1")).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(webhookService);
    }

    @Test
    void simulateIs404WhenSimulationIsSwitchedOffEvenOnTheStub() {
        lenient().when(gateway.name()).thenReturn("stub");
        given(gateway.simulateEnabled()).willReturn(false);
        wallet(true);
        inv.setGatewayOrderId("order_stub_1");

        assertThatThrownBy(() -> service.simulate("T1", "I1")).isInstanceOf(NotFoundException.class);
        verifyNoInteractions(webhookService);
    }
}
