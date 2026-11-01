package com.voyra.crm.service;

import com.voyra.crm.dto.FlightSearchQuery;
import com.voyra.crm.dto.SupplierTestResult;
import com.voyra.crm.enums.PlatformAuditAction;
import com.voyra.crm.models.ResolvedCredential;
import com.voyra.crm.service.booking.SupplierRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A live test search with one supplier row's own key (active or not): DEL to BOM a month out, one adult, through the
 * real provider's client. Separate from {@link PlatformSupplierCredentialService} because the provider depends on that service.
 * No transaction - it makes an HTTP call.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PlatformSupplierTestService {

    private final SupplierRegistry registry;
    private final PlatformSupplierCredentialService credentialService;
    private final PlatformAuditService platformAuditService;

    public SupplierTestResult testCall(String credentialId) {
        ResolvedCredential credential = credentialService.resolve(credentialId);
        long startedAt = System.currentTimeMillis();
        SupplierTestResult result;
        try {
            int offers = registry.flightSearch(credential.provider()).search(query(), credential).size();
            result = new SupplierTestResult(true, credential.name() + " answered with " + offers + " offers",
                    System.currentTimeMillis() - startedAt);
        } catch (RuntimeException e) {
            String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            result = new SupplierTestResult(false, message, System.currentTimeMillis() - startedAt);
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("credentialId", credentialId);
        detail.put("provider", credential.provider().name());
        detail.put("ok", result.isOk());
        detail.put("millis", result.getMillis());
        platformAuditService.record(PlatformAuditAction.CREDENTIAL_TEST, PlatformSupplierCredentialService.ENTITY_TYPE,
                credentialId, detail);
        log.info("Platform supplier test call: provider={}, ok={}, millis={}", credential.provider(), result.isOk(), result.getMillis());
        return result;
    }

    private static FlightSearchQuery query() {
        FlightSearchQuery q = new FlightSearchQuery();
        q.setOrigin("DEL");
        q.setDestination("BOM");
        q.setDepartDate(LocalDate.now().plusDays(30));
        q.setAdults(1);
        return q;
    }
}
