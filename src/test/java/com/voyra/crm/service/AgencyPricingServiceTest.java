package com.voyra.crm.service;

import com.voyra.crm.dto.AgencyPricingAssignRequest;
import com.voyra.crm.dto.AgencyPricingAssignmentResponse;
import com.voyra.crm.dto.PricingRuleCreateRequest;
import com.voyra.crm.dto.PricingRuleResponse;
import com.voyra.crm.dto.PricingRuleUpdateRequest;
import com.voyra.crm.entity.AgencyPricingAssignment;
import com.voyra.crm.entity.PricingRule;
import com.voyra.crm.enums.PlatformAuditAction;
import com.voyra.crm.enums.PricingProduct;
import com.voyra.crm.enums.PricingRuleKind;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.exception.BookingConflictException;
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
        when(ruleRepository.existsByKindAndProductAndNameIgnoreCase(PricingRuleKind.DISCOUNT, PricingProduct.FLIGHT, "Gold")).thenReturn(false);

        PricingRuleResponse r = service.createRule(req);

        assertThat(r.getName()).isEqualTo("Gold");
        assertThat(r.getPct()).isEqualByComparingTo("5.00");
        assertThat(r.getVersion()).isEqualTo(1);
        verify(auditService).record(eq(PlatformAuditAction.PRICING_RULE_CREATE), eq("PRICING_RULE"), anyString(), any());

        when(ruleRepository.existsByKindAndProductAndNameIgnoreCase(PricingRuleKind.DISCOUNT, PricingProduct.FLIGHT, "Gold")).thenReturn(true);
        assertThatThrownBy(() -> service.createRule(req)).isInstanceOf(IllegalStateException.class).hasMessageContaining("Gold");
    }

    @Test
    void anEditInUseAppliesAtOnceMovesTheVersionAndCountsTheAgencies() {
        when(ruleRepository.findByIdForUpdate("S")).thenReturn(Optional.of(silver));
        when(assignmentRepository.findAll()).thenReturn(List.of(
                AgencyPricingAssignment.builder().tenantId("T1").product(PricingProduct.FLIGHT).discountRuleId("S").commissionRuleId("C2").build(),
                AgencyPricingAssignment.builder().tenantId("T2").product(PricingProduct.FLIGHT).discountRuleId("DD").commissionRuleId("DC").build()));
        when(tenantRepository.count()).thenReturn(3L);
        PricingRuleUpdateRequest req = new PricingRuleUpdateRequest();
        req.setName("Silver");
        req.setPct(new BigDecimal("12"));

        PricingRuleResponse r = service.updateRule("S", req);

        assertThat(r.getPct()).isEqualByComparingTo("12.00");
        assertThat(r.getVersion()).isEqualTo(2);
        assertThat(r.getAgencyCount()).isEqualTo(1);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(auditService).record(eq(PlatformAuditAction.PRICING_RULE_UPDATE), eq("PRICING_RULE"), eq("S"), detail.capture());
        assertThat(detail.getValue()).containsEntry("agencyCount", 1L).containsEntry("pctBefore", new BigDecimal("3"))
                .doesNotContainKeys("lossMakingAgencies", "allowAboveEarned", "allowAboveEarnedBefore");
    }

    @Test
    void onlyAPctChangeBumpsTheVersion() {
        when(ruleRepository.findByIdForUpdate("S")).thenReturn(Optional.of(silver));
        PricingRuleUpdateRequest req = new PricingRuleUpdateRequest();
        req.setName("Silver tier");
        req.setPct(new BigDecimal("3"));
        assertThat(service.updateRule("S", req).getVersion()).isEqualTo(1);

        req.setPct(new BigDecimal("4"));
        assertThat(service.updateRule("S", req).getVersion()).isEqualTo(2);
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
    void assignChecksKindAndProductAndAuditsTheRulePairWithoutLossFigures() {
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
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(auditService).record(eq(PlatformAuditAction.PRICING_ASSIGN), eq("AGENCY_PRICING"), eq("T1"), detail.capture());
        assertThat(detail.getValue()).containsEntry("discountRule", "Silver").containsEntry("commissionPct", new BigDecimal("2"))
                .doesNotContainKeys("worstCaseNetPer10k", "lossMaking", "allowCommissionAboveEarned");

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
    void aHotelDuplicateNamesItsKindAndPctIsRounded() {
        PricingRuleCreateRequest req = new PricingRuleCreateRequest();
        req.setKind(PricingRuleKind.COMMISSION);
        req.setProduct(PricingProduct.HOTEL);
        req.setName("Premium");
        req.setPct(new BigDecimal("2.345"));
        when(ruleRepository.existsByKindAndProductAndNameIgnoreCase(PricingRuleKind.COMMISSION, PricingProduct.HOTEL, "Premium"))
                .thenReturn(false, true);

        PricingRuleResponse r = service.createRule(req);
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

        List<AgencyPricingAssignmentResponse> out = service.assignments("T1");

        assertThat(out).extracting(AgencyPricingAssignmentResponse::getProduct).containsExactly(PricingProduct.FLIGHT, PricingProduct.HOTEL);
        AgencyPricingAssignmentResponse flight = out.get(0);
        assertThat(flight.isAssigned()).isTrue();
        assertThat(flight.getUpdatedAt()).isEqualTo(at);
        assertThat(flight.getUpdatedByName()).isEqualTo("admin");
        AgencyPricingAssignmentResponse hotel = out.get(1);
        assertThat(hotel.isAssigned()).isFalse();
        assertThat(hotel.getUpdatedAt()).isNull();
        assertThat(hotel.getUpdatedByName()).isNull();
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
    void reassigningRecordsTheRulesBefore() {
        AgencyPricingAssignment row = AgencyPricingAssignment.builder().tenantId("T1").product(PricingProduct.FLIGHT)
                .discountRuleId("S").commissionRuleId("C2").build();
        when(assignmentRepository.findByTenantIdAndProduct("T1", PricingProduct.FLIGHT)).thenReturn(Optional.of(row));
        when(ruleRepository.findById("DD")).thenReturn(Optional.of(defaultDiscount));
        when(ruleRepository.findById("DC")).thenReturn(Optional.of(defaultCommission));
        AgencyPricingAssignRequest req = new AgencyPricingAssignRequest();
        req.setDiscountRuleId("DD");
        req.setCommissionRuleId("DC");

        AgencyPricingAssignmentResponse r = service.assign("T1", PricingProduct.FLIGHT, req);

        assertThat(r.isAssigned()).isTrue();
        assertThat(row.getDiscountRuleId()).isEqualTo("DD");
        assertThat(row.getUpdatedByName()).isEqualTo("admin@voyra.test");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(auditService).record(eq(PlatformAuditAction.PRICING_ASSIGN), eq("AGENCY_PRICING"), eq("T1"), detail.capture());
        assertThat(detail.getValue()).containsEntry("discountRuleBefore", "S").containsEntry("commissionRuleBefore", "C2");
    }

    @Test
    void aRuleMustExistAndBeOfTheRightProduct() {
        AgencyPricingAssignRequest req = new AgencyPricingAssignRequest();
        req.setDiscountRuleId("NOPE");
        req.setCommissionRuleId("C2");
        when(ruleRepository.findById("NOPE")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.assign("T1", PricingProduct.FLIGHT, req))
                .isInstanceOf(com.voyra.crm.exception.NotFoundException.class).hasMessage("Pricing rule not found: NOPE");

        req.setDiscountRuleId("S");
        when(ruleRepository.findById("S")).thenReturn(Optional.of(silver));
        assertThatThrownBy(() -> service.assign("T1", PricingProduct.HOTEL, req))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Choose a hotel discount rule");
        verify(assignmentRepository, never()).save(any());
    }
}
