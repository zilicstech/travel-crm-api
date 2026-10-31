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
    @Mock private PlatformSupplierCredentialService suppliers;
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
        lenient().when(suppliers.commissionEstimatePct(any())).thenReturn(new BigDecimal("5"));
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

    // ---- coverage of the remaining rule, impact, assignment and preview paths

    private static PricingRule hotelRule(String id, PricingRuleKind kind, String pct, boolean isDefault) {
        return PricingRule.builder().id(id).kind(kind).product(PricingProduct.HOTEL).name("Hotel " + id).pct(new BigDecimal(pct))
                .isDefault(isDefault).version(1).build();
    }

    @Test
    void listSortsByProductKindDefaultFirstThenPctAndNameAndCountsWhoIsOnEachRule() {
        PricingRule gold = rule("G", PricingRuleKind.DISCOUNT, "Gold", "5", false);
        PricingRule bronze = rule("B", PricingRuleKind.DISCOUNT, "Bronze", "3", false);
        PricingRule hotelDiscount = hotelRule("HD", PricingRuleKind.DISCOUNT, "1", false);
        when(ruleRepository.findAll()).thenReturn(List.of(hotelDiscount, gold, standard, silver, defaultCommission, bronze, defaultDiscount));
        when(assignmentRepository.findAll()).thenReturn(List.of(
                AgencyPricingAssignment.builder().tenantId("T1").product(PricingProduct.FLIGHT).discountRuleId("S").commissionRuleId("C2").build()));
        when(tenantRepository.count()).thenReturn(3L);

        List<PricingRuleResponse> out = service.listRules(null);

        assertThat(out).extracting(PricingRuleResponse::getId).containsExactly("DD", "B", "S", "G", "DC", "C2", "HD");
        assertThat(out).extracting(PricingRuleResponse::getAgencyCount).containsExactly(2L, 0L, 1L, 0L, 2L, 1L, 0L);

        when(ruleRepository.findByProduct(PricingProduct.HOTEL)).thenReturn(List.of(hotelDiscount));
        assertThat(service.listRules(PricingProduct.HOTEL)).extracting(PricingRuleResponse::getId).containsExactly("HD");
    }

    @Test
    void aCommissionRuleKeepsAllowAboveEarnedAndAHotelDuplicateNamesItsKind() {
        PricingRuleCreateRequest req = new PricingRuleCreateRequest();
        req.setKind(PricingRuleKind.COMMISSION);
        req.setProduct(PricingProduct.HOTEL);
        req.setName("Premium");
        req.setPct(new BigDecimal("2.345"));
        req.setAllowAboveEarned(true);
        when(ruleRepository.existsByKindAndProductAndNameIgnoreCase(PricingRuleKind.COMMISSION, PricingProduct.HOTEL, "Premium"))
                .thenReturn(false, true);

        PricingRuleResponse r = service.createRule(req);
        assertThat(r.isAllowAboveEarned()).isTrue();
        assertThat(r.getPct()).isEqualByComparingTo("2.35");
        assertThat(r.getAgencyCount()).isZero();

        assertThatThrownBy(() -> service.createRule(req)).isInstanceOf(IllegalStateException.class)
                .hasMessage("A hotel commission rule named \"Premium\" already exists");
    }

    @Test
    void updateRefusesAnUnknownRuleOrATakenName() {
        when(ruleRepository.findByIdForUpdate("X")).thenReturn(Optional.empty());
        PricingRuleUpdateRequest req = new PricingRuleUpdateRequest();
        req.setName("Gold");
        req.setPct(BigDecimal.ONE);
        assertThatThrownBy(() -> service.updateRule("X", req))
                .isInstanceOf(com.voyra.crm.exception.NotFoundException.class).hasMessage("Pricing rule not found");

        when(ruleRepository.findByIdForUpdate("S")).thenReturn(Optional.of(silver));
        when(ruleRepository.existsByKindAndProductAndNameIgnoreCaseAndIdNot(PricingRuleKind.DISCOUNT, PricingProduct.FLIGHT, "Gold", "S"))
                .thenReturn(true);
        assertThatThrownBy(() -> service.updateRule("S", req)).isInstanceOf(IllegalStateException.class)
                .hasMessage("A flight discount rule named \"Gold\" already exists");
        verify(ruleRepository, never()).save(any());
    }

    @Test
    void turningOnAllowAboveEarnedIsAValueChangeAndImpactSeesCommissionRules() {
        when(ruleRepository.findByIdForUpdate("C2")).thenReturn(Optional.of(standard));
        when(ruleRepository.findByProduct(PricingProduct.FLIGHT)).thenReturn(List.of(defaultDiscount, defaultCommission, silver, standard));
        when(assignmentRepository.findByProduct(PricingProduct.FLIGHT)).thenReturn(List.of(
                AgencyPricingAssignment.builder().tenantId("T1").product(PricingProduct.FLIGHT).discountRuleId("S").commissionRuleId("C2").build(),
                AgencyPricingAssignment.builder().tenantId("T2").product(PricingProduct.FLIGHT).discountRuleId("GONE").commissionRuleId("C2").build(),
                AgencyPricingAssignment.builder().tenantId("T3").product(PricingProduct.FLIGHT).discountRuleId("DD").commissionRuleId("DC").build()));
        when(tenantRepository.findAll()).thenReturn(List.of(Tenant.builder().id("T1").agencyName("Sky").build(),
                Tenant.builder().id("T2").agencyName("Sea").build(), Tenant.builder().id("T3").agencyName("Land").build()));
        PricingRuleUpdateRequest req = new PricingRuleUpdateRequest();
        req.setName("Standard");
        req.setPct(new BigDecimal("2"));
        req.setAllowAboveEarned(true);

        PricingRuleResponse r = service.updateRule("C2", req);

        assertThat(r.getVersion()).isEqualTo(2);
        assertThat(r.isAllowAboveEarned()).isTrue();
        // T1 and T2 are on it; T2's discount rule is gone, so only T1 can be judged - and 3 % off loses money
        assertThat(r.getAgencyCount()).isEqualTo(2);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(auditService).record(eq(PlatformAuditAction.PRICING_RULE_UPDATE), eq("PRICING_RULE"), eq("C2"), detail.capture());
        assertThat(detail.getValue()).containsEntry("lossMakingAgencies", List.of("T1"))
                .containsEntry("allowAboveEarnedBefore", false).containsEntry("allowAboveEarned", true);
    }

    @Test
    void aDefaultRuleWithNoOtherDefaultJudgesNobodyAndAnImpactNeedsAValidPct() {
        PricingRule hotelDefault = hotelRule("HDD", PricingRuleKind.DISCOUNT, "0", true);
        when(ruleRepository.findById("HDD")).thenReturn(Optional.of(hotelDefault));
        when(assignmentRepository.findByProduct(PricingProduct.HOTEL)).thenReturn(List.of());
        when(tenantRepository.findAll()).thenReturn(List.of(Tenant.builder().id("T1").build()));
        when(ruleRepository.findByKindAndProductAndIsDefaultTrue(PricingRuleKind.COMMISSION, PricingProduct.HOTEL)).thenReturn(Optional.empty());
        when(ruleRepository.findByProduct(PricingProduct.HOTEL)).thenReturn(List.of(hotelDefault));

        PricingRuleImpactResponse impact = service.impact("HDD", new BigDecimal("50"), true);
        assertThat(impact.getAgencyCount()).isEqualTo(1);
        assertThat(impact.getLossMaking()).isEmpty();

        for (BigDecimal bad : java.util.Arrays.asList(null, new BigDecimal("-0.01"), new BigDecimal("100.01"))) {
            assertThatThrownBy(() -> service.impact("HDD", bad, false)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("The percentage must be between 0 and 100");
        }
        when(ruleRepository.findById("NOPE")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.impact("NOPE", BigDecimal.ONE, false))
                .isInstanceOf(com.voyra.crm.exception.NotFoundException.class);
    }

    @Test
    void deletingAnUnknownRuleOrOneUsedByManyIsRefused() {
        when(ruleRepository.findByIdForUpdate("X")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.deleteRule("X")).isInstanceOf(com.voyra.crm.exception.NotFoundException.class);
        when(ruleRepository.findByIdForUpdate("S")).thenReturn(Optional.of(silver));
        when(assignmentRepository.findUsing("S")).thenReturn(List.of(new AgencyPricingAssignment(), new AgencyPricingAssignment()));
        assertThatThrownBy(() -> service.deleteRule("S")).isInstanceOf(BookingConflictException.class)
                .hasMessage("Used by 2 agencies. Move them to another rule first.");
    }

    @Test
    void assignmentsListBothProductsAndAnUnknownAgencyIsNotFound() {
        when(tenantRepository.existsById("T9")).thenReturn(false);
        assertThatThrownBy(() -> service.assignments("T9")).isInstanceOf(com.voyra.crm.exception.NotFoundException.class)
                .hasMessage("Agency not found: T9");

        java.time.Instant at = java.time.Instant.parse("2026-10-27T10:00:00Z");
        when(assignmentRepository.findByTenantIdAndProduct("T1", PricingProduct.FLIGHT)).thenReturn(Optional.of(
                AgencyPricingAssignment.builder().tenantId("T1").product(PricingProduct.FLIGHT).discountRuleId("S").commissionRuleId("C2")
                        .updatedAt(at).updatedByName("admin").build()));
        when(ruleRepository.findById("S")).thenReturn(Optional.of(silver));
        when(ruleRepository.findById("C2")).thenReturn(Optional.of(standard));
        PricingRule hd = hotelRule("HDD", PricingRuleKind.DISCOUNT, "0", true);
        PricingRule hc = hotelRule("HDC", PricingRuleKind.COMMISSION, "0", true);
        when(assignmentRepository.findByTenantIdAndProduct("T1", PricingProduct.HOTEL)).thenReturn(Optional.empty());
        when(ruleRepository.findByKindAndProductAndIsDefaultTrue(PricingRuleKind.DISCOUNT, PricingProduct.HOTEL)).thenReturn(Optional.of(hd));
        when(ruleRepository.findByKindAndProductAndIsDefaultTrue(PricingRuleKind.COMMISSION, PricingProduct.HOTEL)).thenReturn(Optional.of(hc));
        when(settings.referenceFareBasket(PricingProduct.HOTEL)).thenReturn(List.of(
                new ReferenceFare("Room night", new BigDecimal("8000"), new BigDecimal("6000"))));

        List<AgencyPricingAssignmentResponse> out = service.assignments("T1");

        assertThat(out).extracting(AgencyPricingAssignmentResponse::getProduct).containsExactly(PricingProduct.FLIGHT, PricingProduct.HOTEL);
        AgencyPricingAssignmentResponse flight = out.get(0);
        assertThat(flight.isAssigned()).isTrue();
        assertThat(flight.getUpdatedAt()).isEqualTo(at);
        assertThat(flight.getUpdatedByName()).isEqualTo("admin");
        assertThat(flight.isLossMaking()).isTrue();
        assertThat(flight.getWorstCaseNetPer10k()).isEqualByComparingTo("-153.33");
        AgencyPricingAssignmentResponse hotel = out.get(1);
        assertThat(hotel.isAssigned()).isFalse();
        assertThat(hotel.getUpdatedAt()).isNull();
        assertThat(hotel.getUpdatedByName()).isNull();
        assertThat(hotel.isLossMaking()).isFalse();
        // 5 % earned on a 6000 base over an 8000 gross, per 10k of gross
        assertThat(hotel.getWorstCaseNetPer10k()).isEqualByComparingTo("375.00");
    }

    @Test
    void aMissingDefaultRuleMeansPricingIsNotConfigured() {
        when(assignmentRepository.findByTenantIdAndProduct("T1", PricingProduct.HOTEL)).thenReturn(Optional.empty());
        when(ruleRepository.findByKindAndProductAndIsDefaultTrue(PricingRuleKind.DISCOUNT, PricingProduct.HOTEL)).thenReturn(Optional.empty());
        when(ruleRepository.findByKindAndProductAndIsDefaultTrue(PricingRuleKind.COMMISSION, PricingProduct.HOTEL))
                .thenReturn(Optional.of(hotelRule("HDC", PricingRuleKind.COMMISSION, "0", true)));

        assertThat(service.resolve("T1", PricingProduct.HOTEL)).isEmpty();
        assertThatThrownBy(() -> service.requireInForce("T1", PricingProduct.HOTEL))
                .isInstanceOf(com.voyra.crm.exception.PricingNotConfiguredException.class);
    }

    @Test
    void reassigningRecordsTheRulesBeforeAndAProfitableWorstCase() {
        AgencyPricingAssignment row = AgencyPricingAssignment.builder().tenantId("T1").product(PricingProduct.FLIGHT)
                .discountRuleId("S").commissionRuleId("C2").build();
        when(assignmentRepository.findByTenantIdAndProduct("T1", PricingProduct.FLIGHT)).thenReturn(Optional.of(row));
        when(ruleRepository.findById("DD")).thenReturn(Optional.of(defaultDiscount));
        when(ruleRepository.findById("DC")).thenReturn(Optional.of(defaultCommission));
        AgencyPricingAssignRequest req = new AgencyPricingAssignRequest();
        req.setDiscountRuleId("DD");
        req.setCommissionRuleId("DC");

        AgencyPricingAssignmentResponse r = service.assign("T1", PricingProduct.FLIGHT, req);

        assertThat(r.isLossMaking()).isFalse();
        assertThat(row.getDiscountRuleId()).isEqualTo("DD");
        assertThat(row.getUpdatedByName()).isEqualTo("admin@voyra.test");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(auditService).record(eq(PlatformAuditAction.PRICING_ASSIGN), eq("AGENCY_PRICING"), eq("T1"), detail.capture());
        assertThat(detail.getValue()).containsEntry("discountRuleBefore", "S").containsEntry("commissionRuleBefore", "C2")
                .containsEntry("lossMaking", false).containsEntry("worstCaseNetPer10k", new BigDecimal("244.44"));
    }

    @Test
    void aRuleMustExistAndBeOfTheRightProduct() {
        AgencyPricingAssignRequest req = new AgencyPricingAssignRequest();
        req.setDiscountRuleId("NOPE");
        req.setCommissionRuleId("C2");
        when(ruleRepository.findById("NOPE")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.assign("T1", PricingProduct.FLIGHT, req))
                .isInstanceOf(com.voyra.crm.exception.NotFoundException.class).hasMessage("Pricing rule not found: NOPE");

        when(ruleRepository.findById("S")).thenReturn(Optional.of(silver));
        assertThatThrownBy(() -> service.preview("T1", PricingProduct.HOTEL, "S", "C2"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Choose a hotel discount rule");
        verify(assignmentRepository, never()).save(any());
    }

    @Test
    void previewShowsEachReferenceFareAndTheWorstCase() {
        when(ruleRepository.findById("S")).thenReturn(Optional.of(silver));
        when(ruleRepository.findById("C2")).thenReturn(Optional.of(standard));

        com.voyra.crm.dto.PricingPreviewResponse p = service.preview("T1", PricingProduct.FLIGHT, "S", "C2");

        assertThat(p.getWorstCaseNetPer10k()).isEqualByComparingTo("-153.33");
        assertThat(p.getBasketLines()).extracting(com.voyra.crm.dto.PricingPreviewResponse.BasketLine::getLabel)
                .containsExactly("Domestic LCC", "International");
        assertThat(p.getBasketLines().get(0).getDisplayed()).isEqualByComparingTo("4850.00");
        assertThat(p.getBasketLines().get(0).getVoyraNet()).isEqualByComparingTo("-45.00");
        assertThat(p.getBasketLines().get(1).getDisplayed()).isEqualByComparingTo("43650.00");
        assertThat(p.getBasketLines().get(1).getVoyraNet()).isEqualByComparingTo("-690.00");
    }

    @Test
    void aCommissionRuleWithoutAllowAboveEarnedStaysCappedOnCreateUpdateAndImpact() {
        PricingRuleCreateRequest req = new PricingRuleCreateRequest();
        req.setKind(PricingRuleKind.COMMISSION);
        req.setProduct(PricingProduct.FLIGHT);
        req.setName("Capped");
        req.setPct(new BigDecimal("1"));
        assertThat(service.createRule(req).isAllowAboveEarned()).isFalse();

        when(ruleRepository.findByIdForUpdate("C2")).thenReturn(Optional.of(standard));
        PricingRuleUpdateRequest upd = new PricingRuleUpdateRequest();
        upd.setName("Standard 2");
        upd.setPct(new BigDecimal("2"));
        PricingRuleResponse r = service.updateRule("C2", upd);
        assertThat(r.isAllowAboveEarned()).isFalse();
        assertThat(r.getVersion()).isEqualTo(1);

        when(ruleRepository.findById("C2")).thenReturn(Optional.of(standard));
        when(tenantRepository.findAll()).thenReturn(List.of(Tenant.builder().id("T1").agencyName("Sky").build()));
        when(assignmentRepository.findByProduct(PricingProduct.FLIGHT)).thenReturn(List.of(
                AgencyPricingAssignment.builder().tenantId("T1").product(PricingProduct.FLIGHT).discountRuleId("DD").commissionRuleId("C2").build()));
        when(ruleRepository.findByProduct(PricingProduct.FLIGHT)).thenReturn(List.of(defaultDiscount, standard));
        // capped at the 5 % earned, 50 % commission costs nothing extra; uncapped it loses money
        assertThat(service.impact("C2", new BigDecimal("50"), false).getLossMaking()).isEmpty();
        assertThat(service.impact("C2", new BigDecimal("50"), true).getLossMaking())
                .extracting(PricingRuleImpactResponse.LossMakingAgency::getTenantId).containsExactly("T1");
    }
}
