package com.voyra.crm.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;

@Slf4j
@Service
public class LoggingNotificationService implements NotificationService {

    @Override
    public void lowBalance(String tenantId, BigDecimal available) {
        log.warn("Low wallet balance - tenantId: {}, available: {}", tenantId, available);
    }

    @Override
    public void platformAlert(String subject, String body) {
        log.warn("Platform alert - {}: {}", subject, body);
    }

    @Override
    public void subscriptionExpiring(String tenantId, String kind, Instant endsAt, int daysLeft) {
        log.warn("Subscription expiring - tenantId: {}, {} ends {} ({} day(s) left)", tenantId, kind, endsAt, daysLeft);
    }
}
