package com.voyra.crm.service;

import java.math.BigDecimal;

/** Outbound notices. Slice 6 adds SMTP; until then the logging implementation is the only one. */
public interface NotificationService {

    void lowBalance(String tenantId, BigDecimal available);

    void platformAlert(String subject, String body);
}
