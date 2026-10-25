package com.voyra.crm.service;

import java.math.BigDecimal;
import java.time.Instant;

/** Outbound notices. Slice 6 adds SMTP; until then the logging implementation is the only one. */
public interface NotificationService {

    void lowBalance(String tenantId, BigDecimal available);

    void platformAlert(String subject, String body);

    /** 14/7/1 days before a trial, waiver or paid period ends; {@code kind} is TRIAL, WAIVER or PERIOD. */
    void subscriptionExpiring(String tenantId, String kind, Instant endsAt, int daysLeft);
}
