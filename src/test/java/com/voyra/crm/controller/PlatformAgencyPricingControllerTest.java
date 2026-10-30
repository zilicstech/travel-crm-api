package com.voyra.crm.controller;

import com.voyra.crm.config.SecurityConfig;
import com.voyra.crm.entity.PricingRule;
import com.voyra.crm.enums.DiscountBase;
import com.voyra.crm.enums.PricingProduct;
import com.voyra.crm.enums.PricingRuleKind;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.models.ReferenceFare;
import com.voyra.crm.repository.AgencyPricingAssignmentRepository;
import com.voyra.crm.repository.PricingRuleRepository;
import com.voyra.crm.repository.TenantRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import com.voyra.crm.security.JwtAuthenticationFilter;
import com.voyra.crm.security.JwtService;
import com.voyra.crm.security.RestAuthenticationEntryPoint;
import com.voyra.crm.service.AgencyPricingService;
import com.voyra.crm.service.PlatformAuditService;
import com.voyra.crm.service.PlatformSettingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The real AgencyPricingService over mocked repositories: routes, validation, the JSON shape and the preview guard. */
@WebMvcTest({PlatformAgencyPricingController.class, PlatformPricingRuleController.class})
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class, RestAuthenticationEntryPoint.class,
        AgencyPricingService.class})
class PlatformAgencyPricingControllerTest {

    private static final String AGENCY = "/api/platform/agencies/T1/pricing";
    private static final String RULES = "/api/platform/pricing-rules";

    @Autowired private MockMvc mockMvc;
    @MockBean private PricingRuleRepository ruleRepository;
    @MockBean private AgencyPricingAssignmentRepository assignmentRepository;
    @MockBean private TenantRepository tenantRepository;
    @MockBean private PlatformSettingService settings;
    @MockBean private com.voyra.crm.service.PlatformSupplierCredentialService suppliers;
    @MockBean private PlatformAuditService auditService;

    private static PricingRule rule(String id, PricingRuleKind kind, String pct, boolean isDefault) {
        return PricingRule.builder().id(id).kind(kind).product(PricingProduct.FLIGHT).name(isDefault ? "Default" : id)
                .pct(new BigDecimal(pct)).isDefault(isDefault).version(1).updatedAt(Instant.now()).build();
    }

    private void seed() {
        given(tenantRepository.existsById("T1")).willReturn(true);
        given(settings.discountBase()).willReturn(DiscountBase.GROSS);
        given(suppliers.commissionEstimatePct(org.mockito.ArgumentMatchers.any())).willReturn(new BigDecimal("5"));
        given(settings.referenceFareBasket(PricingProduct.FLIGHT)).willReturn(List.of(
                new ReferenceFare("Domestic LCC", new BigDecimal("5000"), new BigDecimal("3500")),
                new ReferenceFare("Domestic FSC", new BigDecimal("9000"), new BigDecimal("6000")),
                new ReferenceFare("International", new BigDecimal("45000"), new BigDecimal("22000"))));
        given(ruleRepository.findById("D10")).willReturn(Optional.of(rule("D10", PricingRuleKind.DISCOUNT, "10", false)));
        given(ruleRepository.findById("C4")).willReturn(Optional.of(rule("C4", PricingRuleKind.COMMISSION, "4", false)));
        given(ruleRepository.findByKindAndProductAndIsDefaultTrue(PricingRuleKind.DISCOUNT, PricingProduct.FLIGHT))
                .willReturn(Optional.of(rule("DD", PricingRuleKind.DISCOUNT, "0", true)));
        given(ruleRepository.findByKindAndProductAndIsDefaultTrue(PricingRuleKind.COMMISSION, PricingProduct.FLIGHT))
                .willReturn(Optional.of(rule("DC", PricingRuleKind.COMMISSION, "0", true)));
    }

    private static RequestPostProcessor as(UserType type) {
        String role = type == UserType.SUPER_ADMIN ? "ROLE_SUPER_ADMIN" : "ROLE_AGENCY_OWNER";
        return authentication(new UsernamePasswordAuthenticationToken(new CustomUserPrincipal("U1", "admin", type, null), null,
                List.of(new SimpleGrantedAuthority(role))));
    }

    @Test
    void previewOfARulePairFlagsALossMakingPairOnTheSeededBasket() throws Exception {
        seed();
        mockMvc.perform(get(AGENCY + "/preview").param("product", "FLIGHT").param("discountRuleId", "D10").param("commissionRuleId", "C4")
                        .with(as(UserType.SUPER_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.worstCaseNetPer10k").value(-951.11))
                .andExpect(jsonPath("$.basketLines.length()").value(3));
    }

    @Test
    void anUnassignedAgencyShowsTheDefaultRulesForBothProducts() throws Exception {
        seed();
        given(settings.referenceFareBasket(PricingProduct.HOTEL)).willReturn(List.of(
                new ReferenceFare("City hotel", new BigDecimal("5000"), new BigDecimal("4200"))));
        given(ruleRepository.findByKindAndProductAndIsDefaultTrue(PricingRuleKind.DISCOUNT, PricingProduct.HOTEL))
                .willReturn(Optional.of(rule("HD", PricingRuleKind.DISCOUNT, "0", true)));
        given(ruleRepository.findByKindAndProductAndIsDefaultTrue(PricingRuleKind.COMMISSION, PricingProduct.HOTEL))
                .willReturn(Optional.of(rule("HC", PricingRuleKind.COMMISSION, "0", true)));
        mockMvc.perform(get(AGENCY).with(as(UserType.SUPER_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].product").value("FLIGHT"))
                .andExpect(jsonPath("$[0].assigned").value(false))
                .andExpect(jsonPath("$[0].discountRule.isDefault").value(true))
                .andExpect(jsonPath("$[0].lossMaking").value(false));
    }

    @Test
    void createValidatesTheBodyAndAgenciesCannotUseTheRoutes() throws Exception {
        given(ruleRepository.save(any())).willAnswer(i -> i.getArgument(0));
        mockMvc.perform(post(RULES).with(as(UserType.SUPER_ADMIN)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"DISCOUNT\",\"product\":\"FLIGHT\",\"name\":\"Silver\",\"pct\":3}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Silver"))
                .andExpect(jsonPath("$.isDefault").value(false))
                .andExpect(jsonPath("$.agencyCount").value(0));
        mockMvc.perform(post(RULES).with(as(UserType.SUPER_ADMIN)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"DISCOUNT\",\"product\":\"FLIGHT\",\"name\":\"Too much\",\"pct\":101}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(RULES).with(as(UserType.SUPER_ADMIN)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"DISCOUNT\",\"product\":\"FLIGHT\",\"name\":\"Fine\",\"pct\":3.004}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(RULES).with(as(UserType.SUPER_ADMIN)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"DISCOUNT\",\"product\":\"FLIGHT\",\"name\":\" \",\"pct\":3}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(RULES).with(as(UserType.AGENCY_OWNER))).andExpect(status().isForbidden());
        mockMvc.perform(put(AGENCY + "/FLIGHT").with(as(UserType.AGENCY_OWNER)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"discountRuleId\":\"D10\",\"commissionRuleId\":\"C4\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void deletingARuleInUseIs409WithItsCode() throws Exception {
        given(ruleRepository.findByIdForUpdate("D10")).willReturn(Optional.of(rule("D10", PricingRuleKind.DISCOUNT, "10", false)));
        given(assignmentRepository.findUsing("D10")).willReturn(List.of(new com.voyra.crm.entity.AgencyPricingAssignment(),
                new com.voyra.crm.entity.AgencyPricingAssignment()));
        mockMvc.perform(delete(RULES + "/D10").with(as(UserType.SUPER_ADMIN)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RULE_IN_USE"))
                .andExpect(jsonPath("$.message").value("Used by 2 agencies. Move them to another rule first."));
    }
}
