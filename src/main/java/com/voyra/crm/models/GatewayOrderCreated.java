package com.voyra.crm.models;

/** What the gateway returned for a new order; the amount is in paise as the checkout widget wants it. Never serialized. */
public record GatewayOrderCreated(String orderId, long amountPaise, String currency) {
}
