package com.voyra.crm.models;

import java.math.BigDecimal;

/** One payment attempt on a gateway order, amount converted to rupees. Never serialized. */
public record GatewayPayment(String paymentId, String orderId, BigDecimal amountInr, String currency, String status) {
}
