package com.voyra.crm.service;

import com.voyra.crm.dto.AgencyPricingAssignRequest;
import com.voyra.crm.dto.AgencyPricingAssignmentResponse;
import com.voyra.crm.dto.PricingRuleCreateRequest;
import com.voyra.crm.dto.PricingRuleImpactResponse;
import com.voyra.crm.dto.PricingRuleResponse;
import com.voyra.crm.dto.PricingRuleUpdateRequest;
import com.voyra.crm.entity.AgencyPricingAssignment;
import com.voyra.crm.entity.PricingRule;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.enums.DiscountBase;
import com.voyra.crm.enums.PlatformAuditAction;
import com.voyra.crm.enums.PricingProduct;
import com.voyra.crm.enums.PricingRuleKind;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.exception.BookingConflictException;
import com.voyra.crm.models.ReferenceFare;
import com.voyra.crm.repository.AgencyPricingAssignmentRepository;
import com.voyra.crm.repository.PricingRuleRepository;
import com.voyra.crm.repository.TenantRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgencyPricingServiceTest {

    @Mock private PricingRuleRepository ruleRepository;
    @Mock private AgencyPricingAssignmentRepository assignmentRepository;
    @Mock private TenantRepository tenantRepository;
    @Mock private PlatformSettingService settings;
    @Mock private PlatformAuditService auditService;
    @InjectMocks private AgencyPricingService service;

    private final PricingRule defaultDiscount = rule("DD", PricingRuleKind.DISCOUNT, "Default", "0", true);
    private final PricingRule defaultCommission = rule("DC", PricingRuleKind.COMMISSION, "Default", "0", true);
    private final PricingRule silver = rule("S", PricingRuleKind.DISCOUNT, "Silver", "3", false);
    private final PricingRule standard = rule("C2", PricingRuleKind.COMMISSION, "Standard", "2", false);

    private static PricingRule rule(String id, PricingRuleKind kind, String name, String pct, boolean isDefault) {
        return PricingRule.builder().id(id).kind(kind).product(PricingProduct.FLIGHT).name(name).pct(new BigDecimal(pct))
                .isDefault(isDefault).version(1).build();
    }

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal("A1", "admin@voyra.test", UserType.SUPER_ADMIN, null), null, List.of()));
        lenient().when(settings.discountBase()).thenReturn(DiscountBase.GROSS);
        lenient().when(settings.commissionEstimatePct()).thenReturn(new BigDecimal("5"));
        lenient().when(settings.referenceFareBasket(PricingProduct.FLIGHT)).thenReturn(List.of(
                new ReferenceFare("Domestic LCC", new BigDecimal("5000"), new BigDecimal("3500")),
                new ReferenceFare("International", new BigDecimal("45000"), new BigDecimal("22000"))));
        lenient().when(ruleRepository.findByKindAndProductAndIsDefaultTrue(PricingRuleKind.DISCOUNT, PricingProduct.FLIGHT)).thenReturn(Optional.of(defaultDiscount));
        lenient().when(ruleRepository.findByKindAndProductAndIsDefaultTrue(PricingRuleKind.COMMISSION, PricingProduct.FLIGHT)).thenReturn(Optional.of(defaultCommission));
        lenient().when(ruleRepository.save(any(PricingRule.class))).thenAnswer(i -> i.getArgument(0));
        lenient().when(assignmentRepository.save(any(AgencyPricingAssignment.class))).thenAnswer(i -> i.getArgument(0));
        lenient().when(tenantRepository.existsById(anyString())).thenReturn(true);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void anAgencyWithNoAssignmentIsPricedOnTheDefaultRules() {
        when(assignmentRepository.findByTenantIdAndProduct("T1", PricingProduct.FLIGHT)).thenReturn(Optional.empty());

        AgencyPricingService.EffectivePricing p = service.requireInForce("T1", PricingProduct.FLIGHT);

        assertThat(p.discount()).isSameAs(defaultDiscount);
        assertThat(p.commission()).isSameAs(defaultCommission);
        assertThat(p.pricingId()).isEqualTo("D:DD:v1|C:DC:v1");
    }

    @Test
    void anAssignedAgencyIsPricedOnItsRules() {
        when(assignmentRepository.findByTenantIdAndProduct("T1", PricingProduct.FLIGHT)).thenReturn(Optional.of(
                AgencyPricingAssignment.builder().tenantId("T1").product(PricingProduct.FLIGHT).discountRuleId("S").commissionRuleId("C2").build()));
        when(ruleRepository.findById("S")).thenReturn(Optional.of(silver));
        when(ruleRepository.findById("C2")).thenReturn(Optional.of(standard));

        AgencyPricingService.EffectivePricing p = service.requireInForce("T1", PricingProduct.FLIGHT);

        assertThat(p.discount().getPct()).isEqualByComparingTo("3");
        assertThat(p.commission().getPct()).isEqualByComparingTo("2");
    }

    @Test
    void createRefusesADuplicateNameAndAuditsANewRule() {
        PricingRuleCreateRequest req = new PricingRuleCreateRequest();
        req.setKind(PricingRuleKind.DISCOUNT);
        req.setProduct(PricingProduct.FLIGHT);
        req.setName(" Gold ");
        req.setPct(new BigDecimal("5"));
        req.setAllowAboveEarned(true);  // ignored on a discount rule
        when(ruleRepository.existsByKindAndProductAndNameIgnoreCase(PricingRuleKind.DISCOUNT, PricingProduct.FLIGHT, "Gold")).thenReturn(false);

        PricingRuleResponse r = service.createRule(req);

        assertThat(r.getName()).isEqualTo("Gold");
        assertThat(r.getPct()).isEqualByComparingTo("5.00");
        assertThat(r.isAllowAboveEarned()).isFalse();
        assertThat(r.getVersion()).isEqualTo(1);
        verify(auditService).record(eq(PlatformAuditAction.PRICING_RULE_CREATE), eq("PRICING_RULE"), anyString(), any());

        when(ruleRepository.existsByKindAndProductAndNameIgnoreCase(PricingRuleKind.DISCOUNT, PricingProduct.FLIGHT, "Gold")).thenReturn(true);
        assertThatThrownBy(() -> service.createRule(req)).isInstanceOf(IllegalStateException.class).hasMessageContaining("Gold");
    }

    @Test
    void anEditInUseAppliesAtOnceMovesTheVersionAndNamesTheAgenciesItMakesLossMaking() {
        when(ruleRepository.findByIdForUpdate("S")).thenReturn(Optional.of(silver));
        when(ruleRepository.findByProduct(PricingProduct.FLIGHT)).thenReturn(List.of(defaultDiscount, defaultCommission, silver, standard));
        when(assignmentRepository.findByProduct(PricingProduct.FLIGHT)).thenReturn(List.of(
                AgencyPricingAssignment.builder().tenantId("T1").product(PricingProduct.FLIGHT).discountRuleId("S").commissionRuleId("C2").build(),
                AgencyPricingAssignment.builder().tenantId("T2").product(PricingProduct.FLIGHT).discountRuleId("DD").commissionRuleId("DC").build()));
        when(tenantRepository.findAll()).thenReturn(List.of(Tenant.builder().id("T1").agencyName("Sky").build(),
                Tenant.builder().id("T2").agencyName("Sea").build(), Tenant.builder().id("T3").agencyName("Land").build()));
        PricingRuleUpdateRequest req = new PricingRuleUpdateRequest();
        req.setName("Silver");
        req.setPct(new BigDecimal("12"));  // 12 % off with 2 % commission on a 5 % earning loses money

        PricingRuleResponse r = service.updateRule("S", req);

        assertThat(r.getPct()).isEqualByComparingTo("12.00");
        assertThat(r.getVersion()).isEqualTo(2);
        assertThat(r.getAgencyCount()).isEqualTo(1);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(auditService).record(eq(PlatformAuditAction.PRICING_RULE_UPDATE), eq("PRICING_RULE"), eq("S"), detail.capture());
        assertThat(detail.getValue()).containsEntry("lossMakingAgencies", List.of("T1")).containsEntry("agencyCount", 1L);
    }

    @Test
    void aRenameAloneKeepsTheVersionSoReviewedBookingsAreNotRepriced() {
        when(ruleRepository.findByIdForUpdate("S")).thenReturn(Optional.of(silver));
        PricingRuleUpdateRequest req = new PricingRuleUpdateRequest();
        req.setName("Silver tier");
        req.setPct(new BigDecimal("3"));

        assertThat(service.updateRule("S", req).getVersion()).isEqualTo(1);
    }

    @Test
    void theDefaultRuleCountsEveryUnassignedAgencyAndAnImpactCoversThem() {
        when(ruleRepository.findById("DD")).thenReturn(Optional.of(defaultDiscount));
        when(ruleRepository.findByProduct(PricingProduct.FLIGHT)).thenReturn(List.of(defaultDiscount, defaultCommission));
        when(assignmentRepository.findByProduct(PricingProduct.FLIGHT)).thenReturn(List.of());
        when(tenantRepository.findAll()).thenReturn(List.of(Tenant.builder().id("T1").agencyName("Sky").build(),
                Tenant.builder().id("T2").agencyName("Sea").build()));

        PricingRuleImpactResponse impact = service.impact("DD", new BigDecimal("1"), false);

        assertThat(impact.getAgencyCount()).isEqualTo(2);
        assertThat(impact.getLossMaking()).isEmpty();
    }

    @Test
    void aRuleInUseOrTheDefaultCannotBeDeleted() {
        when(ruleRepository.findByIdForUpdate("S")).thenReturn(Optional.of(silver));
        when(assignmentRepository.findUsing("S")).thenReturn(List.of(new AgencyPricingAssignment()));
        assertThatThrownBy(() -> service.deleteRule("S")).isInstanceOf(BookingConflictException.class)
                .hasMessageContaining("Used by 1 agency").extracting("code").isEqualTo("RULE_IN_USE");

        when(ruleRepository.findByIdForUpdate("DD")).thenReturn(Optional.of(defaultDiscount));
        assertThatThrownBy(() -> service.deleteRule("DD")).isInstanceOf(BookingConflictException.class).extracting("code").isEqualTo("DEFAULT_RULE");
        verify(ruleRepository, never()).delete(any());

        when(assignmentRepository.findUsing("S")).thenReturn(List.of());
        service.deleteRule("S");
        verify(ruleRepository).delete(silver);
        verify(auditService).record(eq(PlatformAuditAction.PRICING_RULE_DELETE), eq("PRICING_RULE"), eq("S"), any());
    }

    @Test
    void assignChecksKindAndProductAndAuditsTheWorstCase() {
        when(ruleRepository.findById("S")).thenReturn(Optional.of(silver));
        when(ruleRepository.findById("C2")).thenReturn(Optional.of(standard));
        when(assignmentRepository.findByTenantIdAndProduct("T1", PricingProduct.FLIGHT)).thenReturn(Optional.empty());
        AgencyPricingAssignRequest req = new AgencyPricingAssignRequest();
        req.setDiscountRuleId("S");
        req.setCommissionRuleId("C2");

        // after the save the assignment is read back for the response
        when(assignmentRepository.findByTenantIdAndProduct("T1", PricingProduct.FLIGHT)).thenReturn(Optional.empty(), Optional.of(
                AgencyPricingAssignment.builder().tenantId("T1").product(PricingProduct.FLIGHT).discountRuleId("S").commissionRuleId("C2").build()));
        AgencyPricingAssignmentResponse r = service.assign("T1", PricingProduct.FLIGHT, req);

        assertThat(r.isAssigned()).isTrue();
        assertThat(r.getDiscountRule().getName()).isEqualTo("Silver");
        verify(auditService).record(eq(PlatformAuditAction.PRICING_ASSIGN), eq("AGENCY_PRICING"), eq("T1"), any());

        req.setDiscountRuleId("C2");  // a commission rule in the discount slot
        assertThatThrownBy(() -> service.assign("T1", PricingProduct.FLIGHT, req)).isInstanceOf(IllegalArgumentException.class);
    }
}
