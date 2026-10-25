package com.voyra.crm.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

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
}
