package com.voyra.crm.scheduler;

import com.voyra.crm.context.TenantContext;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.repository.TenantRepository;
import com.voyra.crm.service.BookingCompletionService;
import com.voyra.crm.service.ForexRevaluationTenantService;
import com.voyra.crm.service.LedgerIntegrityCheckService;
import com.voyra.crm.service.LedgerIntegrityCheckService.IntegrityResult;
import com.voyra.crm.service.RevenueRecognitionTenantService;
import com.voyra.crm.support.LogCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The nightly per-tenant jobs share one shape: skip inactive agencies, run each active one under its own
 * TenantContext, log one agency's failure and carry on, and always clear the context afterwards.
 */
@ExtendWith(MockitoExtension.class)
class TenantLoopJobsTest {

    @Mock private TenantRepository tenantRepository;
    @Mock private BookingCompletionService bookingCompletionService;
    @Mock private ForexRevaluationTenantService forexService;
    @Mock private RevenueRecognitionTenantService recognitionService;
    @Mock private LedgerIntegrityCheckService integrityService;

    private final List<String> seenTenants = new ArrayList<>();

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    /** T1 works, T2 is inactive, T3 fails. */
    private void threeTenants() {
        when(tenantRepository.findAll()).thenReturn(List.of(
                Tenant.builder().id("T1").isActive(true).build(),
                Tenant.builder().id("T2").isActive(false).build(),
                Tenant.builder().id("T3").isActive(true).build()));
    }

    private <T> T seen(T result) {
        String tenant = TenantContext.getTenantId();
        seenTenants.add(tenant);
        if ("T3".equals(tenant)) throw new IllegalStateException("tenant T3 is broken");
        return result;
    }

    @Test
    void bookingCompletionRunsEveryActiveTenantAndSurvivesAFailure() {
        threeTenants();
        when(bookingCompletionService.completeFinished(LocalDate.now())).thenAnswer(inv -> seen(4));

        try (LogCapture log = new LogCapture(BookingCompletionJob.class)) {
            new BookingCompletionJob(tenantRepository, bookingCompletionService).run();
            assertThat(log.lines()).contains("ERROR Booking completion failed for tenant T3: tenant T3 is broken",
                    "INFO Booking completion done: 4 booking(s) completed");
        }
        assertThat(seenTenants).containsExactly("T1", "T3");
        assertThat(TenantContext.getTenantId()).isNull();
    }

    @Test
    void forexRevaluationRevaluesAtThePreviousMonthEndPerTenant() {
        threeTenants();
        when(forexService.revalueOpenForeignBills(LocalDate.now().minusDays(1)))
                .thenAnswer(inv -> seen(new ForexRevaluationTenantService.RevaluationResult(2)));

        try (LogCapture log = new LogCapture(ForexRevaluationJob.class)) {
            new ForexRevaluationJob(tenantRepository, forexService).run();
            assertThat(log.lines()).contains("ERROR Forex revaluation failed for tenant T3: tenant T3 is broken",
                    "INFO Forex revaluation complete: 1 tenant(s) processed, 2 bill(s) revalued");
        }
        assertThat(seenTenants).containsExactly("T1", "T3");
        assertThat(TenantContext.getTenantId()).isNull();
    }

    @Test
    void revenueRecognitionRecognisesDueInvoicesPerTenant() {
        threeTenants();
        when(recognitionService.recognizeDue(LocalDate.now()))
                .thenAnswer(inv -> seen(new RevenueRecognitionTenantService.RecognitionResult(5, 3)));

        try (LogCapture log = new LogCapture(RevenueRecognitionJob.class)) {
            new RevenueRecognitionJob(tenantRepository, recognitionService).run();
            assertThat(log.lines()).contains("ERROR Revenue recognition failed for tenant T3: tenant T3 is broken",
                    "INFO Revenue recognition complete: 1 tenant(s) processed, 3 invoice(s) recognized");
        }
        assertThat(seenTenants).containsExactly("T1", "T3");
        assertThat(TenantContext.getTenantId()).isNull();
    }

    @Test
    void ledgerIntegrityCountsCleanTenantsAndSurvivesAFailure() {
        threeTenants();
        when(integrityService.check()).thenAnswer(inv -> seen(new IntegrityResult(10, List.of(), BigDecimal.ZERO)));

        try (LogCapture log = new LogCapture(LedgerIntegrityJob.class)) {
            new LedgerIntegrityJob(tenantRepository, integrityService).run();
            assertThat(log.lines()).contains("ERROR Ledger integrity check failed for tenant T3: tenant T3 is broken",
                    "INFO Ledger integrity check complete: 1/1 tenant(s) clean");
        }
        assertThat(seenTenants).containsExactly("T1", "T3");
        assertThat(TenantContext.getTenantId()).isNull();
    }

    @Test
    void ledgerIntegrityNamesUnbalancedEntriesAndANonZeroTrialBalance() {
        when(tenantRepository.findAll()).thenReturn(List.of(
                Tenant.builder().id("T1").isActive(true).build(),
                Tenant.builder().id("T2").isActive(true).build(),
                Tenant.builder().id("T3").isActive(true).build()));
        when(integrityService.check()).thenReturn(
                new IntegrityResult(3, List.of("JE-1"), BigDecimal.ZERO),
                new IntegrityResult(3, List.of(), new BigDecimal("0.01")),
                new IntegrityResult(3, List.of("JE-2"), new BigDecimal("-5")));

        try (LogCapture log = new LogCapture(LedgerIntegrityJob.class)) {
            new LedgerIntegrityJob(tenantRepository, integrityService).run();
            assertThat(log.lines()).containsExactly(
                    "ERROR Ledger integrity: tenant T1 has unbalanced journal entries: [JE-1]",
                    "ERROR Ledger integrity: tenant T2 trial balance does not net to zero - net 0.01",
                    "ERROR Ledger integrity: tenant T3 has unbalanced journal entries: [JE-2]",
                    "ERROR Ledger integrity: tenant T3 trial balance does not net to zero - net -5",
                    "INFO Ledger integrity check complete: 0/3 tenant(s) clean");
        }
        verify(tenantRepository).findAll();
    }
}
