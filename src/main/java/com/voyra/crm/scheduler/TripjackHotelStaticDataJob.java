package com.voyra.crm.scheduler;

import com.voyra.crm.context.TenantContext;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.repository.TenantRepository;
import com.voyra.crm.service.tripjack.TripjackHotelSearchProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps Tripjack's hotel city list and nationalities loaded, so no search ever waits on them: shortly after startup,
 * then hourly - a run only calls Tripjack for a host whose copy is missing or a week old, so it reloads weekly and
 * retries within the hour after a failure. The data is the same for every agency on a host; the credential comes from
 * whichever agency is first checked (it lives in the tenant schema), the rest find it fresh and skip.
 */
@Component
@ConditionalOnProperty(name = "app.hotel-supplier.provider", havingValue = "tripjack")
@RequiredArgsConstructor
@Slf4j
public class TripjackHotelStaticDataJob {

    private final TenantRepository tenantRepository;
    private final TripjackHotelSearchProvider tripjackHotelSearchProvider;

    @Scheduled(initialDelayString = "PT5S", fixedDelayString = "PT1H")
    public void run() {
        for (Tenant tenant : tenantRepository.findAll()) {
            if (!Boolean.TRUE.equals(tenant.getIsActive())) {
                continue;
            }
            TenantContext.setTenantId(tenant.getId());
            try {
                tripjackHotelSearchProvider.refreshStaticData();
            } catch (IllegalStateException e) {
                // No Tripjack credential in this agency - nothing to load for it.
            } catch (Exception e) {
                log.warn("Tripjack hotel static data refresh failed for tenant {}: {}", tenant.getId(), e.getMessage());
            } finally {
                TenantContext.clear();
            }
        }
    }
}
