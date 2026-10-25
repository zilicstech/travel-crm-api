package com.voyra.crm.service.payments;

import com.voyra.crm.models.GatewayOrderCreated;
import com.voyra.crm.models.GatewayPayment;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** The hosted-checkout gateway, picked by app.payments.provider (razorpay | stub). */
public interface PaymentGateway {

    GatewayOrderCreated createOrder(BigDecimal amountInr, String receipt, Map<String, String> notes);

    List<GatewayPayment> fetchOrderPayments(String gatewayOrderId);

    /** True when {@code signature} is the gateway's signature of exactly these body bytes. */
    boolean verifyWebhook(String rawBody, String signature);

    /** The publishable key the browser checkout needs; never the secret. */
    String publicKeyId();

    String name();
}
