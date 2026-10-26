package com.voyra.crm.service;

import com.voyra.crm.entity.GatewayOrder;
import com.voyra.crm.entity.GatewayWebhookOrphan;
import com.voyra.crm.entity.PlatformReceivable;
import com.voyra.crm.entity.TopupRequest;
import com.voyra.crm.entity.Wallet;
import com.voyra.crm.entity.WalletEntry;
import com.voyra.crm.enums.GatewayOrderPurpose;
import com.voyra.crm.enums.GatewayOrderStatus;
import com.voyra.crm.enums.ReceivableReason;
import com.voyra.crm.enums.ReceivableStatus;
import com.voyra.crm.enums.TopupMode;
import com.voyra.crm.enums.TopupStatus;
import com.voyra.crm.enums.WalletEntryType;
import com.voyra.crm.enums.WalletStatus;
import com.voyra.crm.repository.GatewayOrderRepository;
import com.voyra.crm.repository.GatewayWebhookOrphanRepository;
import com.voyra.crm.repository.PlatformReceivableRepository;
import com.voyra.crm.repository.TenantRepository;
import com.voyra.crm.repository.TopupRequestRepository;
import com.voyra.crm.repository.WalletEntryRepository;
import com.voyra.crm.repository.WalletRepository;
import com.voyra.crm.service.GatewayWebhookService.WebhookOutcome;
import com.voyra.crm.service.payments.PaymentGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GatewayWebhookServiceTest {

    @Mock
    private GatewayOrderRepository orderRepository;
    @Mock
    private GatewayWebhookOrphanRepository orphanRepository;
    @Mock
    private TopupRequestRepository topupRepository;
    @Mock
    private WalletRepository walletRepository;
    @Mock
    private WalletService walletService;
    @Mock
    private NotificationService notificationService;
    @Mock
    private PaymentGateway gateway;
    @Mock
    private SubscriptionBillingService billingService;
    @Mock
    private WalletEntryRepository entryRepository;
    @Mock
    private PlatformReceivableRepository receivableRepository;
    @Mock
    private TenantRepository tenantRepository;

    private GatewayWebhookService service;

    @BeforeEach
    void setUp() {
        service = new GatewayWebhookService(orderRepository, orphanRepository, topupRepository, walletRepository,
                walletService, notificationService, gateway, billingService, 5);
        given(gateway.verifyWebhook(anyString(), any())).willReturn(true);
        given(walletRepository.findById("W1")).willReturn(Optional.of(wallet(WalletStatus.OPEN)));
        given(walletService.chargebacksPosted("TOPUP_REQUEST", "R1")).willReturn(BigDecimal.ZERO);
    }

    private static Wallet wallet(WalletStatus status) {
        return Wallet.builder().id("W1").tenantId("T1").status(status).build();
    }

    private static String payment(String event, String paymentId, long paise, String currency) {
        return payment(event, paymentId, paise, currency, "captured");
    }

    private static String payment(String event, String paymentId, long paise, String currency, String status) {
        return "{\"event\":\"" + event + "\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"" + paymentId
                + "\",\"order_id\":\"order_1\",\"amount\":" + paise + ",\"currency\":\"" + currency
                + "\",\"status\":\"" + status + "\",\"card\":{\"last4\":\"1111\"}}}}}";
    }

    private static String orderPaid(String paymentId) {
        return "{\"event\":\"order.paid\",\"payload\":{\"order\":{\"entity\":{\"id\":\"order_1\",\"amount\":50000}},"
                + "\"payment\":{\"entity\":{\"id\":\"" + paymentId + "\",\"order_id\":\"order_1\",\"amount\":50000,"
                + "\"currency\":\"INR\",\"status\":\"captured\"}}}}";
    }

    private static String reversal(String event, String kind, String id, long paise) {
        return reversal(event, kind, id, paise, "pay_1");
    }

    private static String reversal(String event, String kind, String id, long paise, String paymentId) {
        return "{\"event\":\"" + event + "\",\"payload\":{\"" + kind + "\":{\"entity\":{\"id\":\"" + id
                + "\",\"payment_id\":\"" + paymentId + "\",\"amount\":" + paise + "}},\"payment\":{\"entity\":{\"id\":\"" + paymentId + "\","
                + "\"order_id\":\"order_1\",\"amount\":50000,\"currency\":\"INR\",\"status\":\"refunded\"}}}}";
    }

    private GatewayOrder order(GatewayOrderPurpose purpose) {
        GatewayOrder o = GatewayOrder.builder().id("G1").gatewayOrderId("order_1").purpose(purpose).refId("R1")
                .amount(new BigDecimal("500.00")).status(GatewayOrderStatus.CREATED).build();
        given(orderRepository.findByGatewayOrderIdForUpdate("order_1")).willReturn(Optional.of(o));
        return o;
    }

    private TopupRequest topup(TopupStatus status) {
        TopupRequest t = TopupRequest.builder().id("R1").tenantId("T1").walletId("W1").mode(TopupMode.GATEWAY)
                .amount(new BigDecimal("500.00")).status(status).gatewayOrderId("order_1")
                .approvedAmount(status == TopupStatus.APPROVED ? new BigDecimal("500.00") : null)
                .gatewayPaymentId(status == TopupStatus.APPROVED ? "pay_1" : null).build();
        given(topupRepository.findById("R1")).willReturn(Optional.of(t));
        return t;
    }

    @Test
    void duplicateCapturedAndOrderPaidCreditOnce() {
        GatewayOrder o = order(GatewayOrderPurpose.TOPUP);
        TopupRequest t = topup(TopupStatus.PENDING);
        given(walletService.credit(eq("T1"), eq(WalletEntryType.TOPUP), eq(new BigDecimal("500.00")), eq("gw:pay_1"),
                anyString(), eq("R1"), anyString())).willReturn(WalletEntry.builder().id("E1").build());

        assertThat(service.handle(payment("payment.captured", "pay_1", 50000, "INR"), "sig")).isEqualTo(WebhookOutcome.PROCESSED);
        service.handle(orderPaid("pay_1"), "sig");
        service.handle(payment("payment.captured", "pay_1", 50000, "INR"), "sig");

        verify(walletService, times(1)).credit(any(), any(), any(), any(), any(), any(), any());
        assertThat(t.getStatus()).isEqualTo(TopupStatus.APPROVED);
        assertThat(t.getApprovedAmount()).isEqualByComparingTo("500.00");
        assertThat(t.getGatewayPaymentId()).isEqualTo("pay_1");
        assertThat(t.getCreditEntryId()).isEqualTo("E1");
        assertThat(o.getStatus()).isEqualTo(GatewayOrderStatus.PAID);
        assertThat(o.getEvents()).hasSize(3);
        verify(notificationService, times(1)).topupApproved(t);
        assertThat(o.getEvents().get(0)).containsOnlyKeys("id", "event", "at").containsEntry("id", "pay_1")
                .containsEntry("event", "payment.captured");
    }

    @Test
    void unknownOrderRetriesThenAlertsAfterFiveAttempts() {
        given(orderRepository.findByGatewayOrderIdForUpdate("order_1")).willReturn(Optional.empty());
        AtomicReference<GatewayWebhookOrphan> stored = new AtomicReference<>();
        given(orphanRepository.findByGatewayOrderIdAndEvent("order_1", "payment.captured"))
                .willAnswer(i -> Optional.ofNullable(stored.get()));
        given(orphanRepository.save(any(GatewayWebhookOrphan.class))).willAnswer(i -> {
            stored.set(i.getArgument(0));
            return i.getArgument(0);
        });
        String body = payment("payment.captured", "pay_1", 50000, "INR");

        for (int attempt = 1; attempt <= 4; attempt++) {
            assertThat(service.handle(body, "sig")).isEqualTo(WebhookOutcome.RETRY);
        }
        verify(notificationService, never()).platformAlert(anyString(), anyString());
        assertThat(service.handle(body, "sig")).isEqualTo(WebhookOutcome.IGNORED);
        assertThat(service.handle(body, "sig")).isEqualTo(WebhookOutcome.IGNORED);

        verify(notificationService, times(1)).platformAlert(anyString(), anyString());
        assertThat(stored.get().getAttempts()).isEqualTo(6);
        assertThat(stored.get().getPayload()).doesNotContainKey("card");
        verifyNoInteractions(walletService, topupRepository);
    }

    @Test
    void amountMismatchNeverCredits() {
        String[] mismatches = {
                payment("payment.captured", "pay_1", 49900, "INR"),
                payment("payment.captured", "pay_1", 50000, "USD"),
                payment("payment.captured", "pay_1", 50000, "INR", "authorized")};
        for (String body : mismatches) {
            GatewayOrder o = order(GatewayOrderPurpose.TOPUP);
            TopupRequest t = topup(TopupStatus.PENDING);

            assertThat(service.handle(body, "sig")).isEqualTo(WebhookOutcome.PROCESSED);
            assertThat(o.getStatus()).isEqualTo(GatewayOrderStatus.FLAGGED);
            // a later valid capture on a FLAGGED order is not credited either, but alerts: the customer paid
            assertThat(service.handle(orderPaid("pay_3"), "sig")).isEqualTo(WebhookOutcome.IGNORED);
            assertThat(t.getStatus()).isEqualTo(TopupStatus.PENDING);
        }
        verifyNoInteractions(walletService);
        verify(notificationService, times(3)).platformAlert(eq("Gateway payment does not match its order"), anyString());
        verify(notificationService, times(3)).platformAlert(eq("Captured payment on a flagged order"), contains("pay_3"));
    }

    @Test
    void captureOntoAClosedWalletFlagsAndAlertsWithoutRetry() {
        GatewayOrder o = order(GatewayOrderPurpose.TOPUP);
        TopupRequest t = topup(TopupStatus.PENDING);
        given(walletRepository.findById("W1")).willReturn(Optional.of(wallet(WalletStatus.CLOSED)));

        assertThat(service.handle(payment("payment.captured", "pay_1", 50000, "INR"), "sig")).isEqualTo(WebhookOutcome.PROCESSED);

        assertThat(o.getStatus()).isEqualTo(GatewayOrderStatus.FLAGGED);
        assertThat(o.getEvents()).hasSize(1);
        assertThat(t.getStatus()).isEqualTo(TopupStatus.PENDING);
        verify(notificationService).platformAlert(anyString(), contains("refund"));
        verify(walletService, never()).credit(any(), any(), any(), any(), any(), any(), any());

        service.handle(orderPaid("pay_1"), "sig");  // the same payment's order.paid: already alerted, not again
        verify(notificationService, times(1)).platformAlert(anyString(), anyString());
    }

    @Test
    void aValidCaptureOnAFlaggedOrderAlertsWithoutCrediting() {
        GatewayOrder o = order(GatewayOrderPurpose.TOPUP);
        o.setStatus(GatewayOrderStatus.FLAGGED);
        TopupRequest t = topup(TopupStatus.PENDING);

        assertThat(service.handle(payment("payment.captured", "pay_2", 50000, "INR"), "sig")).isEqualTo(WebhookOutcome.IGNORED);

        verify(notificationService).platformAlert(eq("Captured payment on a flagged order"), contains("pay_2"));
        verifyNoInteractions(walletService);
        assertThat(t.getStatus()).isEqualTo(TopupStatus.PENDING);
        assertThat(o.getStatus()).isEqualTo(GatewayOrderStatus.FLAGGED);
    }

    @Test
    void subscriptionCaptureDeliveredTwicePaysTheInvoiceOnceAndNeverTouchesAWallet() {
        GatewayOrder o = order(GatewayOrderPurpose.SUBSCRIPTION);
        given(billingService.markPaid("R1", "pay_1")).willReturn(true, false);

        assertThat(service.handle(payment("payment.captured", "pay_1", 50000, "INR"), "sig")).isEqualTo(WebhookOutcome.PROCESSED);
        assertThat(service.handle(orderPaid("pay_1"), "sig")).isEqualTo(WebhookOutcome.IGNORED);
        service.handle(reversal("refund.processed", "refund", "rfnd_1", 50000), "sig");

        assertThat(o.getStatus()).isEqualTo(GatewayOrderStatus.PAID);
        verify(billingService, times(2)).markPaid("R1", "pay_1");
        verifyNoInteractions(walletService, topupRepository);
    }

    @Test
    void subscriptionCaptureForTheWrongAmountIsFlaggedAndNotPaid() {
        GatewayOrder o = order(GatewayOrderPurpose.SUBSCRIPTION);

        service.handle(payment("payment.captured", "pay_1", 40000, "INR"), "sig");

        assertThat(o.getStatus()).isEqualTo(GatewayOrderStatus.FLAGGED);
        verifyNoInteractions(billingService, walletService);
    }

    @Test
    void subscriptionCaptureTheInvoiceRefusedFlagsTheOrderSoNothingRetriesIt() {
        GatewayOrder o = order(GatewayOrderPurpose.SUBSCRIPTION);
        given(billingService.markPaid("R1", "pay_1")).willReturn(false);

        assertThat(service.handle(payment("payment.captured", "pay_1", 50000, "INR"), "sig")).isEqualTo(WebhookOutcome.IGNORED);

        // captured but not applied (invoice already paid or void): billing alerted; FLAGGED keeps reconciliation off it
        assertThat(o.getStatus()).isEqualTo(GatewayOrderStatus.FLAGGED);
        verifyNoInteractions(walletService);
    }

    @Test
    void paymentFailedKeepsTheTopupPendingAndALaterCaptureCredits() {
        GatewayOrder o = order(GatewayOrderPurpose.TOPUP);
        TopupRequest t = topup(TopupStatus.PENDING);
        given(walletService.credit(any(), any(), any(), any(), any(), any(), any())).willReturn(WalletEntry.builder().id("E1").build());

        service.handle(payment("payment.failed", "pay_1", 50000, "INR", "failed"), "sig");
        assertThat(t.getStatus()).isEqualTo(TopupStatus.PENDING);
        assertThat(o.getStatus()).isEqualTo(GatewayOrderStatus.CREATED);
        assertThat(o.getEvents()).hasSize(1).first().satisfies(e -> assertThat(e).containsEntry("event", "payment.failed"));
        verifyNoInteractions(walletService);

        service.handle(payment("payment.captured", "pay_2", 50000, "INR"), "sig");
        assertThat(t.getStatus()).isEqualTo(TopupStatus.APPROVED);
        verify(walletService).credit(any(), any(), any(), eq("gw:pay_2"), any(), any(), any());
    }

    @Test
    void refundAfterCreditDebitsOrOpensAChargebackReceivable() {
        order(GatewayOrderPurpose.TOPUP);
        TopupRequest t = topup(TopupStatus.APPROVED);
        t.setCreditEntryId("E1");

        service.handle(reversal("refund.processed", "refund", "rfnd_1", 20000), "sig");
        service.handle(reversal("payment.dispute.created", "dispute", "disp_1", 30000), "sig");

        verify(walletService).debitOrReceivable("T1", WalletEntryType.ADJUSTMENT, new BigDecimal("200.00"), "gw-refund:rfnd_1",
                "TOPUP_REQUEST", "R1", ReceivableReason.CHARGEBACK, null, "Gateway refund rfnd_1 on top-up R1");
        verify(walletService).debitOrReceivable("T1", WalletEntryType.ADJUSTMENT, new BigDecimal("300.00"), "gw-refund:disp_1",
                "TOPUP_REQUEST", "R1", ReceivableReason.CHARGEBACK, null, "Card dispute disp_1 on top-up R1");
    }

    @Test
    void disputeThenRefundOnOnePaymentReversesAtMostTheCredit() {
        order(GatewayOrderPurpose.TOPUP);
        TopupRequest t = topup(TopupStatus.APPROVED);
        t.setCreditEntryId("E1");
        given(walletService.chargebacksPosted("TOPUP_REQUEST", "R1"))
                .willReturn(BigDecimal.ZERO, new BigDecimal("300.00"), new BigDecimal("500.00"));

        service.handle(reversal("payment.dispute.created", "dispute", "disp_1", 30000), "sig");
        service.handle(reversal("refund.processed", "refund", "rfnd_1", 50000), "sig");
        service.handle(reversal("refund.processed", "refund", "rfnd_2", 10000), "sig");

        verify(walletService).debitOrReceivable("T1", WalletEntryType.ADJUSTMENT, new BigDecimal("300.00"), "gw-refund:disp_1",
                "TOPUP_REQUEST", "R1", ReceivableReason.CHARGEBACK, null, "Card dispute disp_1 on top-up R1");
        verify(walletService).debitOrReceivable("T1", WalletEntryType.ADJUSTMENT, new BigDecimal("200.00"), "gw-refund:rfnd_1",
                "TOPUP_REQUEST", "R1", ReceivableReason.CHARGEBACK, null, "Gateway refund rfnd_1 on top-up R1");
        verify(walletService, times(2)).debitOrReceivable(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void refundOfASecondUncreditedPaymentDoesNotDebitTheAgency() {
        order(GatewayOrderPurpose.TOPUP);
        TopupRequest t = topup(TopupStatus.APPROVED);  // credited by pay_1
        t.setCreditEntryId("E1");

        assertThat(service.handle(reversal("refund.processed", "refund", "rfnd_2", 50000, "pay_2"), "sig"))
                .isEqualTo(WebhookOutcome.IGNORED);
        assertThat(service.handle(reversal("payment.dispute.created", "dispute", "disp_2", 50000, "pay_2"), "sig"))
                .isEqualTo(WebhookOutcome.IGNORED);

        verify(walletService, never()).debitOrReceivable(any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(notificationService, times(2)).platformAlert(anyString(), contains("never credited"));
    }

    @Test
    void refundBeforeCaptureIsRetried() {
        order(GatewayOrderPurpose.TOPUP);
        topup(TopupStatus.PENDING);

        assertThat(service.handle(reversal("refund.processed", "refund", "rfnd_1", 20000), "sig")).isEqualTo(WebhookOutcome.RETRY);

        verify(walletService, never()).debitOrReceivable(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void refundOnAFlaggedOrderIsIgnoredNotRetried() {
        GatewayOrder o = order(GatewayOrderPurpose.TOPUP);
        o.setStatus(GatewayOrderStatus.FLAGGED);
        topup(TopupStatus.PENDING);

        assertThat(service.handle(reversal("refund.processed", "refund", "rfnd_1", 50000), "sig")).isEqualTo(WebhookOutcome.IGNORED);

        verifyNoInteractions(walletService);
    }

    @Test
    void badSignatureIs400() {
        given(gateway.verifyWebhook(anyString(), any())).willReturn(false);

        assertThatThrownBy(() -> service.handle(payment("payment.captured", "pay_1", 50000, "INR"), "forged"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(orderRepository, orphanRepository, topupRepository, walletService);
    }

    @Test
    void refundOnASubscriptionOrderAlertsAndLeavesTheInvoiceAlone() {
        order(GatewayOrderPurpose.SUBSCRIPTION);

        assertThat(service.handle(reversal("refund.processed", "refund", "rfnd_1", 50000), "sig")).isEqualTo(WebhookOutcome.IGNORED);

        verify(notificationService).platformAlert(anyString(), contains("rfnd_1"));
        verifyNoInteractions(billingService, walletService);
    }

    @Test
    void disputeCreatedAlertsOnceNamingTheAgencyPaymentDisputeAndAmount() {
        order(GatewayOrderPurpose.TOPUP);
        topup(TopupStatus.APPROVED).setCreditEntryId("E1");

        assertThat(service.handle(reversal("payment.dispute.created", "dispute", "disp_1", 30000), "sig"))
                .isEqualTo(WebhookOutcome.PROCESSED);
        service.handle(reversal("payment.dispute.created", "dispute", "disp_1", 30000), "sig");

        verify(notificationService, times(1)).platformAlert(anyString(), argThat(m -> m.contains("T1") && m.contains("pay_1")
                && m.contains("disp_1") && m.contains("300.00")));
        verify(walletService, times(1)).debitOrReceivable(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void disputeWonWithNothingTakenDoesNothing() {
        order(GatewayOrderPurpose.TOPUP);
        topup(TopupStatus.APPROVED).setCreditEntryId("E1");

        assertThat(service.handle(reversal("payment.dispute.won", "dispute", "disp_9", 30000), "sig"))
                .isEqualTo(WebhookOutcome.IGNORED);

        verify(walletService, never()).credit(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void disputeLostMovesNoMoneyAndClosedAlertsOnlyWhenUndecided() {
        order(GatewayOrderPurpose.TOPUP);
        topup(TopupStatus.APPROVED).setCreditEntryId("E1");
        service.handle(reversal("payment.dispute.created", "dispute", "disp_1", 30000), "sig");
        service.handle(reversal("payment.dispute.created", "dispute", "disp_2", 10000), "sig");

        service.handle(reversal("payment.dispute.lost", "dispute", "disp_1", 30000), "sig");
        service.handle(reversal("payment.dispute.closed", "dispute", "disp_1", 30000), "sig");
        service.handle(reversal("payment.dispute.closed", "dispute", "disp_2", 10000), "sig");

        verify(walletService, never()).credit(any(), any(), any(), any(), any(), any(), any());
        verify(notificationService, never()).platformAlert(anyString(), argThat(m -> m.contains("disp_1") && m.contains("closed")));
        verify(notificationService).platformAlert(anyString(), argThat(m -> m.contains("disp_2") && m.contains("closed")));
    }

    // Round trips through a real WalletService over in-memory rows, so the balance and the receivable are checked.

    private final List<WalletEntry> entries = new ArrayList<>();
    private final List<PlatformReceivable> receivables = new ArrayList<>();

    private GatewayWebhookService withRealWallet(Wallet w) {
        given(walletRepository.findByTenantIdForUpdate("T1")).willReturn(Optional.of(w));
        given(walletRepository.findById("W1")).willReturn(Optional.of(w));
        given(walletRepository.save(any())).willAnswer(i -> i.getArgument(0));
        given(entryRepository.save(any())).willAnswer(i -> {
            entries.add(i.getArgument(0));
            return i.getArgument(0);
        });
        given(entryRepository.findByIdempotencyKey(anyString())).willAnswer(i -> entries.stream()
                .filter(e -> e.getIdempotencyKey().equals(i.getArgument(0))).findFirst());
        given(entryRepository.sumAmountByTypeAndRef(any(), anyString(), anyString())).willAnswer(i -> entries.stream()
                .filter(e -> e.getType() == i.getArgument(0) && Objects.equals(e.getRefType(), i.getArgument(1))
                        && Objects.equals(e.getRefId(), i.getArgument(2)))
                .map(WalletEntry::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
        given(receivableRepository.save(any())).willAnswer(i -> {
            PlatformReceivable r = i.getArgument(0);
            if (!receivables.contains(r)) {
                receivables.add(r);
            }
            return r;
        });
        given(receivableRepository.findByTenantIdAndStatusOrderByCreatedAt("T1", ReceivableStatus.DUE))
                .willAnswer(i -> receivables.stream().filter(r -> r.getStatus() == ReceivableStatus.DUE).toList());
        given(receivableRepository.sumOriginalAmountByReasonAndRef(any(), anyString(), anyString())).willAnswer(i -> receivables.stream()
                .filter(r -> r.getReason() == i.getArgument(0) && Objects.equals(r.getRefId(), i.getArgument(2)))
                .map(PlatformReceivable::getOriginalAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
        WalletService real = new WalletService(walletRepository, entryRepository, receivableRepository, topupRepository,
                tenantRepository, notificationService);
        return new GatewayWebhookService(orderRepository, orphanRepository, topupRepository, walletRepository,
                real, notificationService, gateway, billingService, 5);
    }

    private static Wallet funded(String balance) {
        return Wallet.builder().id("W1").tenantId("T1").status(WalletStatus.OPEN).balance(new BigDecimal(balance))
                .held(BigDecimal.ZERO).createdAt(Instant.now()).build();
    }

    @Test
    void disputeWonAfterAFullDebitRestoresTheBalance() {
        Wallet w = funded("500.00");
        GatewayWebhookService svc = withRealWallet(w);
        order(GatewayOrderPurpose.TOPUP);
        topup(TopupStatus.APPROVED).setCreditEntryId("E1");

        svc.handle(reversal("payment.dispute.created", "dispute", "disp_1", 30000), "sig");
        assertThat(w.getBalance()).isEqualByComparingTo("200.00");
        assertThat(entries.get(0).getNarration()).isEqualTo("Card dispute disp_1 on top-up R1");

        assertThat(svc.handle(reversal("payment.dispute.won", "dispute", "disp_1", 30000), "sig"))
                .isEqualTo(WebhookOutcome.PROCESSED);

        assertThat(w.getBalance()).isEqualByComparingTo("500.00");
        WalletEntry won = entries.get(entries.size() - 1);
        assertThat(won.getType()).isEqualTo(WalletEntryType.ADJUSTMENT);
        assertThat(won.getAmount()).isEqualByComparingTo("300.00");
        assertThat(won.getIdempotencyKey()).isEqualTo("gw-dispute-won:disp_1");
        assertThat(won.getNarration()).isEqualTo("Card dispute disp_1 won - reversed");
    }

    @Test
    void disputeWonAfterAPartialDebitSettlesTheReceivableAndRestoresTheBalance() {
        Wallet w = funded("100.00");
        GatewayWebhookService svc = withRealWallet(w);
        order(GatewayOrderPurpose.TOPUP);
        topup(TopupStatus.APPROVED).setCreditEntryId("E1");

        svc.handle(reversal("payment.dispute.created", "dispute", "disp_1", 30000), "sig");
        assertThat(w.getBalance()).isEqualByComparingTo("0.00");
        assertThat(receivables).singleElement().satisfies(r -> assertThat(r.getAmount()).isEqualByComparingTo("200.00"));

        svc.handle(reversal("payment.dispute.won", "dispute", "disp_1", 30000), "sig");

        assertThat(w.getBalance()).isEqualByComparingTo("100.00");
        assertThat(receivables.get(0).getStatus()).isEqualTo(ReceivableStatus.SETTLED);
        assertThat(receivables.get(0).getAmount()).isEqualByComparingTo("0");
    }

    @Test
    void disputeWonReplayedCreditsOnce() {
        Wallet w = funded("500.00");
        GatewayWebhookService svc = withRealWallet(w);
        order(GatewayOrderPurpose.TOPUP);
        topup(TopupStatus.APPROVED).setCreditEntryId("E1");

        svc.handle(reversal("payment.dispute.created", "dispute", "disp_1", 30000), "sig");
        svc.handle(reversal("payment.dispute.won", "dispute", "disp_1", 30000), "sig");
        int posted = entries.size();
        svc.handle(reversal("payment.dispute.won", "dispute", "disp_1", 30000), "sig");

        assertThat(entries).hasSize(posted);
        assertThat(w.getBalance()).isEqualByComparingTo("500.00");
    }
}
