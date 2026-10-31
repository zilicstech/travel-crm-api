package com.voyra.crm.service;

import com.voyra.crm.context.TenantContext;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The current agency's base currency - every amount its books hold is in it. Set when the agency is
 * created and never changed, so it is cached for good. The {@code *Inr} columns and fields across
 * the accounting tables predate this: they hold base-currency amounts, whatever the base is.
 */
@Component
@RequiredArgsConstructor
public class BaseCurrency {

    public static final String DEFAULT = "INR";

    private final TenantRepository tenantRepository;
    private final Map<String, String> byTenant = new ConcurrentHashMap<>();

    public String code() {
        String tenantId = TenantContext.getTenantId();
        return tenantId == null ? DEFAULT : of(tenantId);
    }

    public String of(String tenantId) {
        return byTenant.computeIfAbsent(tenantId, id -> tenantRepository.findById(id)
                .map(Tenant::getBaseCurrencyCode).filter(c -> !c.isBlank()).orElse(DEFAULT));
    }
}
