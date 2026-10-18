package com.voyra.crm.scheduler;

import com.voyra.crm.context.TenantContext;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.repository.TenantRepository;
import com.voyra.crm.service.BookingCompletionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Nightly: a confirmed booking whose trip is over becomes Completed. Same per-tenant shape as
 * {@link RevenueRecognitionJob} - one tenant's failure is logged and the rest still run.
 * {@code app.bookings.completion.enabled} must be false on all but one instance when scaled out.
 */
@Component
@ConditionalOnProperty(name = "app.bookings.completion.enabled", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class BookingCompletionJob {

    private final TenantRepository tenantRepository;
    private final BookingCompletionService bookingCompletionService;

    @Scheduled(cron = "${app.bookings.completion.cron:0 0 2 * * *}")
    public void run() {
        LocalDate today = LocalDate.now();
        int completed = 0;
        for (Tenant tenant : tenantRepository.findAll()) {
            if (!Boolean.TRUE.equals(tenant.getIsActive())) {
                continue;
            }
            TenantContext.setTenantId(tenant.getId());
            try {
                completed += bookingCompletionService.completeFinished(today);
            } catch (Exception e) {
                log.error("Booking completion failed for tenant {}: {}", tenant.getId(), e.getMessage(), e);
            } finally {
                TenantContext.clear();
            }
        }
        log.info("Booking completion done: {} booking(s) completed", completed);
    }
}
