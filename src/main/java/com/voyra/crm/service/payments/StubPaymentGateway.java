package com.voyra.crm.service.payments;

import com.voyra.crm.models.GatewayOrderCreated;
import com.voyra.crm.models.GatewayPayment;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Stand-in until the Razorpay keys arrive: fake order ids, and payments arrive only through the simulate endpoint. */
public class StubPaymentGateway implements PaymentGateway {

    private final boolean simulateEnabled;

    public StubPaymentGateway(boolean simulateEnabled) {
        this.simulateEnabled = simulateEnabled;
    }

    @Override
    public GatewayOrderCreated createOrder(BigDecimal amountInr, String receipt, Map<String, String> notes) {
        return new GatewayOrderCreated("order_stub_" + UUID.randomUUID().toString().replace("-", ""),
                RazorpayPaymentGateway.toPaise(amountInr), "INR");
    }

    @Override
    public List<GatewayPayment> fetchOrderPayments(String gatewayOrderId) {
        return List.of();
    }

    /** Signature checking is skipped for simulated events; the public webhook is closed while this gateway is active. */
    @Override
    public boolean verifyWebhook(String rawBody, String signature) {
        return true;
    }

    @Override
    public String publicKeyId() {
        return "stub";
    }

    @Override
    public String name() {
        return "stub";
    }

    @Override
    public boolean simulateEnabled() {
        return simulateEnabled;
    }
}
