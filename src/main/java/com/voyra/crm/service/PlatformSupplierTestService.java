package com.voyra.crm.service;

import com.voyra.crm.dto.FlightSearchQuery;
import com.voyra.crm.dto.SupplierTestResult;
import com.voyra.crm.enums.PlatformAuditAction;
import com.voyra.crm.models.ResolvedCredential;
import com.voyra.crm.service.tripjack.TripjackFlightSearchProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
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

    static final String DISABLED = "Tripjack provider is disabled (FLIGHT_SUPPLIER=mock)";

    private final ObjectProvider<TripjackFlightSearchProvider> flightProvider;
    private final PlatformSupplierCredentialService credentialService;
    private final PlatformAuditService platformAuditService;

    public SupplierTestResult testCall(String credentialId) {
        ResolvedCredential credential = credentialService.resolve(credentialId);
        TripjackFlightSearchProvider tripjack = flightProvider.getIfAvailable();
        SupplierTestResult result;
        if (tripjack == null) {
            result = new SupplierTestResult(false, DISABLED, 0);
        } else {
            long startedAt = System.currentTimeMillis();
            try {
                int offers = tripjack.search(query(), credential).size();
                result = new SupplierTestResult(true, "Tripjack answered with " + offers + " offers",
                        System.currentTimeMillis() - startedAt);
            } catch (RuntimeException e) {
                String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                result = new SupplierTestResult(false, message, System.currentTimeMillis() - startedAt);
            }
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
