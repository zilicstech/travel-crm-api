package com.voyra.crm.service;

import com.voyra.crm.entity.GatewayOrder;
import com.voyra.crm.entity.TopupRequest;
import com.voyra.crm.entity.Wallet;
import com.voyra.crm.enums.GatewayOrderPurpose;
import com.voyra.crm.enums.GatewayOrderStatus;
import com.voyra.crm.enums.TopupMode;
import com.voyra.crm.enums.TopupStatus;
import com.voyra.crm.enums.WalletStatus;
import com.voyra.crm.repository.GatewayOrderRepository;
import com.voyra.crm.repository.GatewayWebhookOrphanRepository;
import com.voyra.crm.repository.TopupRequestRepository;
import com.voyra.crm.repository.WalletRepository;
import com.voyra.crm.service.GatewayWebhookService.WebhookOutcome;
import com.voyra.crm.service.payments.PaymentGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The webhook branches the main suite leaves out, under strict stubbing. */
@ExtendWith(MockitoExtension.class)
class GatewayWebhookServiceCoverageTest {

    @Mock private GatewayOrderRepository orderRepository;
    @Mock private GatewayWebhookOrphanRepository orphanRepository;
    @Mock private TopupRequestRepository topupRepository;
    @Mock private WalletRepository walletRepository;
    @Mock private WalletService walletService;
    @Mock private NotificationService notificationService;
    @Mock private PaymentGateway gateway;
    @Mock private SubscriptionBillingService billingService;

    private GatewayWebhookService service;

    @BeforeEach
    void setUp() {
        service = new GatewayWebhookService(orderRepository, orphanRepository, topupRepository, walletRepository,
                walletService, notificationService, gateway, billingService, 5);
    }

    private void signed() {
        when(gateway.verifyWebhook(anyString(), eq("sig"))).thenReturn(true);
    }

    private static String capture(String paymentId) {
        return "{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"" + paymentId
                + "\",\"order_id\":\"order_1\",\"amount\":50000,\"currency\":\"INR\",\"status\":\"captured\"}}}}";
    }

    private static String dispute(String event, String id, long paise) {
        return "{\"event\":\"" + event + "\",\"payload\":{\"dispute\":{\"entity\":{\"id\":\"" + id + "\",\"payment_id\":\"pay_1\",\"amount\":"
                + paise + "}},\"payment\":{\"entity\":{\"id\":\"pay_1\",\"order_id\":\"order_1\"}}}}";
    }

    private GatewayOrder order(GatewayOrderPurpose purpose, GatewayOrderStatus status, List<Map<String, Object>> events) {
        GatewayOrder o = GatewayOrder.builder().id("G1").gatewayOrderId("order_1").purpose(purpose).refId("R1")
                .amount(new BigDecimal("500.00")).status(status).events(new ArrayList<>(events)).build();
        when(orderRepository.findByGatewayOrderIdForUpdate("order_1")).thenReturn(Optional.of(o));
        return o;
    }

    private TopupRequest topup(TopupStatus status, String paymentId) {
        TopupRequest t = TopupRequest.builder().id("R1").tenantId("T1").walletId("W1").mode(TopupMode.GATEWAY)
                .amount(new BigDecimal("500.00")).status(status).gatewayPaymentId(paymentId)
                .approvedAmount(new BigDecimal("500.00")).creditEntryId("E1").build();
        when(topupRepository.findById("R1")).thenReturn(Optional.of(t));
        return t;
    }

    @Test
    void anUnreadableSignedBodyIsRefused() {
        signed();
        assertThatThrownBy(() -> service.handle("{not json", "sig"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Unreadable webhook body");
        verifyNoInteractions(orderRepository);
    }

    @Test
    void anEventWithoutANameOrAnOrderIsIgnored() {
        signed();
        assertThat(service.handle("{\"payload\":{\"payment\":{\"entity\":{\"order_id\":\"order_1\"}}}}", "sig")).isEqualTo(WebhookOutcome.IGNORED);
        assertThat(service.handle("{\"event\":\"payment.captured\",\"payload\":{}}", "sig")).isEqualTo(WebhookOutcome.IGNORED);
        verifyNoInteractions(orderRepository, orphanRepository);
    }

    @Test
    void anOrderInAnotherCurrencyIsFlaggedNotCredited() {
        signed();
        GatewayOrder o = order(GatewayOrderPurpose.TOPUP, GatewayOrderStatus.CREATED, List.of());
        o.setCurrency("USD");

        assertThat(service.handle(capture("pay_1"), "sig")).isEqualTo(WebhookOutcome.PROCESSED);

        assertThat(o.getStatus()).isEqualTo(GatewayOrderStatus.FLAGGED);
        verify(notificationService).platformAlert(eq("Gateway payment does not match its order"), anyString());
        verifyNoInteractions(walletService);
    }

    @Test
    void aFlaggedOrderAlertsOncePerPaymentAndNeverForAMismatch() {
        signed();
        GatewayOrder o = order(GatewayOrderPurpose.TOPUP, GatewayOrderStatus.FLAGGED,
                List.of(Map.of("id", "pay_1", "event", "payment.captured")));

        assertThat(service.handle(capture("pay_1"), "sig")).isEqualTo(WebhookOutcome.IGNORED);
        o.setAmount(new BigDecimal("999.00"));
        assertThat(service.handle(capture("pay_2"), "sig")).isEqualTo(WebhookOutcome.IGNORED);

        verifyNoInteractions(notificationService, walletService);
    }

    @Test
    void aSecondCapturedPaymentOnACreditedTopupIsAlertedForRefund() {
        signed();
        order(GatewayOrderPurpose.TOPUP, GatewayOrderStatus.PAID, List.of());
        topup(TopupStatus.APPROVED, "pay_1");

        assertThat(service.handle(capture("pay_2"), "sig")).isEqualTo(WebhookOutcome.IGNORED);

        verify(notificationService).platformAlert(eq("Second captured payment on a credited top-up"),
                argThat(m -> m.contains("already credited by pay_1") && m.contains("pay_2")));
        verifyNoInteractions(walletService);
    }

    @Test
    void aZeroRefundIsIgnored() {
        signed();
        order(GatewayOrderPurpose.TOPUP, GatewayOrderStatus.PAID, List.of());

        String zero = "{\"event\":\"refund.processed\",\"payload\":{\"refund\":{\"entity\":{\"id\":\"rfnd_1\",\"payment_id\":\"pay_1\",\"amount\":0}},"
                + "\"payment\":{\"entity\":{\"id\":\"pay_1\",\"order_id\":\"order_1\"}}}}";
        assertThat(service.handle(zero, "sig")).isEqualTo(WebhookOutcome.IGNORED);
        verifyNoInteractions(topupRepository, walletService);
    }

    @Test
    void aRefundOnAnOrderWithNoPurposeIsIgnored() {
        signed();
        order(null, GatewayOrderStatus.PAID, List.of());

        assertThat(service.handle(dispute("payment.dispute.created", "disp_1", 100), "sig")).isEqualTo(WebhookOutcome.IGNORED);
        verifyNoInteractions(topupRepository, walletService, notificationService);
    }

    @Test
    void aWonDisputeOnAClosedWalletIsPaidBackOffline() {
        signed();
        order(GatewayOrderPurpose.TOPUP, GatewayOrderStatus.PAID,
                List.of(Map.of("id", "disp_1", "event", "payment.dispute.created", "taken", "300.00")));
        topup(TopupStatus.APPROVED, "pay_1");
        when(walletRepository.findById("W1")).thenReturn(Optional.of(Wallet.builder().id("W1").status(WalletStatus.CLOSED).build()));

        assertThat(service.handle(dispute("payment.dispute.won", "disp_1", 30000), "sig")).isEqualTo(WebhookOutcome.PROCESSED);

        verify(notificationService).platformAlert(eq("Card dispute won on a closed wallet"), argThat(m -> m.contains("300.00")));
        verify(walletService, never()).credit(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void aWonDisputeWhoseWalletIsGoneIsAlsoPaidBackOffline() {
        signed();
        order(GatewayOrderPurpose.TOPUP, GatewayOrderStatus.PAID,
                List.of(Map.of("id", "disp_1", "event", "payment.dispute.created", "taken", "300.00")));
        topup(TopupStatus.APPROVED, "pay_1");

        assertThat(service.handle(dispute("payment.dispute.won", "disp_1", 30000), "sig")).isEqualTo(WebhookOutcome.PROCESSED);
        verify(notificationService).platformAlert(eq("Card dispute won on a closed wallet"), anyString());
    }

    @Test
    void aClosedDisputeThatWasDecidedOrClosedBeforeRaisesNoAlert() {
        signed();
        order(GatewayOrderPurpose.TOPUP, GatewayOrderStatus.PAID, List.of(
                Map.of("id", "disp_1", "event", "payment.dispute.won"),
                Map.of("id", "disp_2", "event", "payment.dispute.closed"),
                Map.of("id", "disp_3", "event", "payment.dispute.created"),
                Map.of("id", "disp_4", "event", "payment.dispute.lost")));

        assertThat(service.handle(dispute("payment.dispute.closed", "disp_1", 100), "sig")).isEqualTo(WebhookOutcome.IGNORED);
        assertThat(service.handle(dispute("payment.dispute.closed", "disp_2", 100), "sig")).isEqualTo(WebhookOutcome.IGNORED);
        assertThat(service.handle(dispute("payment.dispute.closed", "disp_4", 100), "sig")).isEqualTo(WebhookOutcome.IGNORED);

        verifyNoInteractions(notificationService);
    }

    @Test
    void anOrderWithoutItsTopupIsAnError() {
        signed();
        order(GatewayOrderPurpose.TOPUP, GatewayOrderStatus.CREATED, List.of());

        assertThatThrownBy(() -> service.handle(capture("pay_1"), "sig"))
                .isInstanceOf(IllegalStateException.class).hasMessage("Gateway order order_1 has no top-up");
    }
}
