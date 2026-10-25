package com.voyra.crm.service;

import com.voyra.crm.dto.AgencySubscriptionResponse;
import com.voyra.crm.dto.SubscriptionAdminResponse;
import com.voyra.crm.dto.SubscriptionUpdateRequest;
import com.voyra.crm.dto.SubscriptionWaiveRequest;
import com.voyra.crm.dto.TrialExtendRequest;
import com.voyra.crm.entity.Subscription;
import com.voyra.crm.entity.SubscriptionInvoice;
import com.voyra.crm.enums.BillingCycle;
import com.voyra.crm.enums.PlatformAuditAction;
import com.voyra.crm.enums.Sku;
import com.voyra.crm.enums.SubscriptionPlan;
import com.voyra.crm.enums.SubscriptionStatus;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.enums.WaivedReason;
import com.voyra.crm.exception.NotFoundException;
import com.voyra.crm.models.Entitlements;
import com.voyra.crm.repository.AgentRepository;
import com.voyra.crm.repository.SubscriptionInvoiceRepository;
import com.voyra.crm.repository.SubscriptionRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubscriptionServiceTest {

    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private SubscriptionInvoiceRepository invoiceRepository;
    @Mock private AgentRepository agentRepository;
    @Mock private com.voyra.crm.repository.TenantRepository tenantRepository;
    @Mock private EntitlementService entitlementService;
    @Mock private PlatformAuditService auditService;
    @InjectMocks private SubscriptionService service;

    private Subscription sub;

    @BeforeEach
    void setUp() {
        sub = Subscription.builder().id("S1").tenantId("T1").plan(SubscriptionPlan.SUITE)
                .skus(EnumSet.allOf(Sku.class).stream().map(Enum::name).toList()).seats(0)
                .status(SubscriptionStatus.ACTIVE).billingCycle(BillingCycle.MONTHLY)
                .waivedReason(WaivedReason.COMPLIMENTARY).periodEnd(Instant.now().plus(Duration.ofDays(30)))
                .updatedAt(Instant.now()).build();
        lenient().when(subscriptionRepository.findByTenantIdForUpdate("T1")).thenReturn(Optional.of(sub));
        lenient().when(subscriptionRepository.findByTenantId("T1")).thenReturn(Optional.of(sub));
        lenient().when(entitlementService.refresh("T1")).thenReturn(new Entitlements(SubscriptionPlan.SUITE,
                SubscriptionStatus.ACTIVE, EnumSet.allOf(Sku.class), 5, false, null));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private SubscriptionUpdateRequest update(SubscriptionPlan plan, List<Sku> skus, int seats, BillingCycle cycle) {
        SubscriptionUpdateRequest r = new SubscriptionUpdateRequest();
        r.setPlan(plan);
        r.setSkus(skus);
        r.setSeats(seats);
        r.setBillingCycle(cycle);
        return r;
    }

    @Test
    void updateChangesThePlanRefreshesTheCacheAndAudits() {
        service.update("T1", update(SubscriptionPlan.CUSTOM, List.of(Sku.CRM_CORE, Sku.STAFF_SEATS), 3, BillingCycle.ANNUAL));

        assertThat(sub.getPlan()).isEqualTo(SubscriptionPlan.CUSTOM);
        assertThat(sub.getSkus()).containsExactly("CRM_CORE", "STAFF_SEATS");
        assertThat(sub.getSeats()).isEqualTo(3);
        assertThat(sub.getBillingCycle()).isEqualTo(BillingCycle.ANNUAL);
        InOrder order = inOrder(subscriptionRepository, entitlementService, auditService);
        order.verify(subscriptionRepository).save(sub);
        order.verify(auditService).record(eq(PlatformAuditAction.SUBSCRIPTION_CHANGE), eq("TENANT"), eq("T1"), any());
        order.verify(entitlementService).refresh("T1");
    }

    @Test
    void movingAStarterOntoAPaidPlanStartsAPeriodSoTheJobBillsIt() {
        sub.setPlan(SubscriptionPlan.STARTER);
        sub.setWaivedReason(null);
        sub.setPeriodStart(null);
        sub.setPeriodEnd(null);

        service.update("T1", update(SubscriptionPlan.SUITE, List.of(), 0, BillingCycle.MONTHLY));

        assertThat(sub.getPeriodStart()).isNotNull();
        assertThat(sub.getPeriodEnd()).isNotNull();
        assertThat(sub.getPeriodEnd()).isBeforeOrEqualTo(Instant.now());  // due now: the next daily run raises the first invoice
    }

    @Test
    void suiteStoresEverySkuAndStarterNone() {
        service.update("T1", update(SubscriptionPlan.STARTER, List.of(Sku.VISA), 4, BillingCycle.MONTHLY));
        assertThat(sub.getSkus()).isEmpty();
        assertThat(sub.getSeats()).isZero();

        service.update("T1", update(SubscriptionPlan.SUITE, List.of(), 2, BillingCycle.MONTHLY));
        assertThat(sub.getSkus()).hasSize(Sku.values().length);
        assertThat(sub.getSeats()).isEqualTo(2);
    }

    @Test
    void updateRejectsNegativeSeats() {
        assertThatThrownBy(() -> service.update("T1", update(SubscriptionPlan.SUITE, List.of(), -1, BillingCycle.MONTHLY)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(subscriptionRepository, never()).save(any());
    }

    @Test
    void waiveSetsTheReasonAndEndAndRefreshes() {
        Instant until = Instant.now().plus(Duration.ofDays(60));
        sub.setWaivedReason(null);
        sub.setStatus(SubscriptionStatus.READ_ONLY);
        SubscriptionWaiveRequest r = new SubscriptionWaiveRequest();
        r.setReason(WaivedReason.GMV_THRESHOLD);
        r.setUntil(until);

        service.waive("T1", r);

        assertThat(sub.getWaivedReason()).isEqualTo(WaivedReason.GMV_THRESHOLD);
        assertThat(sub.getPeriodEnd()).isEqualTo(until);
        assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        verify(entitlementService).refresh("T1");
        verify(auditService).record(eq(PlatformAuditAction.SUBSCRIPTION_WAIVE), eq("TENANT"), eq("T1"), any());
    }

    @Test
    void waiveRejectsAnEndInThePast() {
        SubscriptionWaiveRequest r = new SubscriptionWaiveRequest();
        r.setReason(WaivedReason.COMPLIMENTARY);
        r.setUntil(Instant.now().minusSeconds(60));
        assertThatThrownBy(() -> service.waive("T1", r)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void extendTrialMovesToTrialClearsTheWaiverAndRefreshes() {
        TrialExtendRequest r = new TrialExtendRequest();
        r.setDays(7);
        Instant before = Instant.now();

        service.extendTrial("T1", r);

        assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.TRIAL);
        assertThat(sub.getWaivedReason()).isNull();
        assertThat(sub.getTrialEndsAt()).isAfter(before.plus(Duration.ofDays(7)).minusSeconds(5));
        assertThat(sub.getPeriodEnd()).isEqualTo(sub.getTrialEndsAt());
        verify(entitlementService).refresh("T1");
        verify(auditService).record(eq(PlatformAuditAction.SUBSCRIPTION_TRIAL_EXTEND), eq("TENANT"), eq("T1"), any());
    }

    @Test
    void extendTrialAddsToARunningTrial() {
        Instant end = Instant.now().plus(Duration.ofDays(3));
        sub.setStatus(SubscriptionStatus.TRIAL);
        sub.setTrialEndsAt(end);
        TrialExtendRequest r = new TrialExtendRequest();
        r.setDays(7);
        service.extendTrial("T1", r);
        assertThat(sub.getTrialEndsAt()).isEqualTo(end.plus(Duration.ofDays(7)));
    }

    @Test
    void agencyViewShowsSeatsAndInvoicesButNeverTheWaiverReason() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal("O1", "owner", UserType.AGENCY_OWNER, "T1"), null, List.of()));
        when(entitlementService.resolve("T1")).thenReturn(new Entitlements(SubscriptionPlan.SUITE, SubscriptionStatus.ACTIVE,
                EnumSet.allOf(Sku.class), 5, false, null));
        when(agentRepository.countByTenantIdAndIsActiveTrue("T1")).thenReturn(2L);
        when(invoiceRepository.findByTenantIdAndStatusNotOrderByCreatedAtDesc("T1", "DRAFT")).thenReturn(List.of(
                SubscriptionInvoice.builder().id("I1").tenantId("T1").number("VOY-SUB-2026-1")
                        .total(new BigDecimal("5898.82")).status("ISSUED").createdAt(Instant.now()).build()));

        AgencySubscriptionResponse r = service.agencyView();

        assertThat(r.getSeatsUsed()).isEqualTo(2);
        assertThat(r.getSeatsTotal()).isEqualTo(5);
        assertThat(r.isWaived()).isTrue();
        assertThat(r.getWaivedLabel()).isEqualTo("Complimentary");
        assertThat(r.getInvoices()).hasSize(1);
        assertThat(r.getInvoices().get(0).getNumber()).isEqualTo("VOY-SUB-2026-1");
        assertThat(r.toString()).doesNotContain("COMPLIMENTARY");
    }

    @Test
    void adminViewIncludesTheReasonAndSeatUsage() {
        when(entitlementService.resolve("T1")).thenReturn(new Entitlements(SubscriptionPlan.SUITE, SubscriptionStatus.ACTIVE,
                EnumSet.allOf(Sku.class), 5, false, null));
        when(agentRepository.countByTenantIdAndIsActiveTrue("T1")).thenReturn(1L);
        SubscriptionAdminResponse r = service.adminView("T1");
        assertThat(r.getWaivedReason()).isEqualTo(WaivedReason.COMPLIMENTARY);
        assertThat(r.getSeatsUsed()).isEqualTo(1);
    }

    @Test
    void unknownTenantIs404() {
        when(subscriptionRepository.findByTenantId("NOPE")).thenReturn(Optional.empty());
        when(tenantRepository.existsById("NOPE")).thenReturn(false);
        assertThatThrownBy(() -> service.adminView("NOPE")).isInstanceOf(NotFoundException.class);
        verify(entitlementService, never()).startMissingTrial(any());
    }

    @Test
    void aTenantWithoutARowGetsTheTrialInsteadOf404() {
        Subscription trial = EntitlementService.newTrial("S9", "T9", Instant.now());
        when(subscriptionRepository.findByTenantId("T9")).thenReturn(Optional.empty());
        when(tenantRepository.existsById("T9")).thenReturn(true);
        when(entitlementService.startMissingTrial("T9")).thenReturn(trial);
        when(entitlementService.resolve("T9")).thenReturn(new Entitlements(SubscriptionPlan.SUITE, SubscriptionStatus.TRIAL,
                EnumSet.allOf(Sku.class), 5, false, null));
        assertThat(service.adminView("T9").getStatus()).isEqualTo(SubscriptionStatus.TRIAL);
    }

    @Test
    void auditIsRecordedBeforeTheCacheRefresh() {
        service.update("T1", update(SubscriptionPlan.SUITE, List.of(), 1, BillingCycle.MONTHLY));
        InOrder order = inOrder(auditService, entitlementService);
        order.verify(auditService).record(eq(PlatformAuditAction.SUBSCRIPTION_CHANGE), any(), any(), any());
        order.verify(entitlementService).refresh("T1");
    }

    @Test
    void waiveIsRefusedOnStarterAndCancelled() {
        SubscriptionWaiveRequest r = new SubscriptionWaiveRequest();
        r.setReason(WaivedReason.COMPLIMENTARY);
        sub.setPlan(SubscriptionPlan.STARTER);
        assertThatThrownBy(() -> service.waive("T1", r)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Set a plan before waiving");
        sub.setPlan(SubscriptionPlan.SUITE);
        sub.setStatus(SubscriptionStatus.CANCELLED);
        assertThatThrownBy(() -> service.waive("T1", r)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Set a plan before waiving");
    }

    @Test
    void customSeatsNeedTheStaffSeatsSku() {
        assertThatThrownBy(() -> service.update("T1", update(SubscriptionPlan.CUSTOM, List.of(Sku.CRM_CORE), 2, BillingCycle.MONTHLY)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Add the Staff seats SKU to give seats");
        verify(subscriptionRepository, never()).save(any());
    }

    @Test
    void updateWaiveAndExtendTrialAllLockTheRow() {
        SubscriptionWaiveRequest w = new SubscriptionWaiveRequest();
        w.setReason(WaivedReason.COMPLIMENTARY);
        TrialExtendRequest t = new TrialExtendRequest();
        t.setDays(7);
        service.update("T1", update(SubscriptionPlan.SUITE, List.of(), 0, BillingCycle.MONTHLY));
        service.waive("T1", w);
        sub.setWaivedReason(null);
        service.extendTrial("T1", t);
        verify(subscriptionRepository, org.mockito.Mockito.times(3)).findByTenantIdForUpdate("T1");
        verify(subscriptionRepository, never()).findByTenantId(any());
    }

    @Test
    void readsNeverTakeTheLockBecauseTheirTransactionIsReadOnly() {
        when(entitlementService.resolve("T1")).thenReturn(new Entitlements(SubscriptionPlan.SUITE, SubscriptionStatus.ACTIVE,
                EnumSet.allOf(Sku.class), 5, false, null));
        service.adminView("T1");
        verify(subscriptionRepository, never()).findByTenantIdForUpdate(any());
    }

    @Test
    void movingToStarterResetsStatusTrialPeriodAndWaiver() {
        sub.setStatus(SubscriptionStatus.READ_ONLY);
        sub.setTrialEndsAt(Instant.now().minusSeconds(60));
        service.update("T1", update(SubscriptionPlan.STARTER, List.of(), 0, BillingCycle.MONTHLY));
        assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(sub.getTrialEndsAt()).isNull();
        assertThat(sub.getPeriodStart()).isNull();
        assertThat(sub.getPeriodEnd()).isNull();
        assertThat(sub.getWaivedReason()).isNull();
    }

    @Test
    void extendTrialRefusesAFoundingAgency() {
        sub.setWaivedReason(WaivedReason.FOUNDING_AGENCY);
        sub.setPeriodEnd(null);
        TrialExtendRequest t = new TrialExtendRequest();
        t.setDays(7);
        assertThatThrownBy(() -> service.extendTrial("T1", t)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Founding agencies don't need a trial");
        verify(subscriptionRepository, never()).save(any());
    }
}
