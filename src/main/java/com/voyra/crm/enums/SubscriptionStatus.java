package com.voyra.crm.enums;

/** Billing state of an agency's subscription. READ_ONLY keeps reads; CANCELLED falls back to Starter. */
public enum SubscriptionStatus {
    TRIAL, ACTIVE, PAST_DUE, READ_ONLY, CANCELLED
}
