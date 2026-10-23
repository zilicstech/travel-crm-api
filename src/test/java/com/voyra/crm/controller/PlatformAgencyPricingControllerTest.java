package com.voyra.crm.controller;

import com.voyra.crm.config.SecurityConfig;
import com.voyra.crm.entity.AgencyPricing;
import com.voyra.crm.enums.DiscountBase;
import com.voyra.crm.enums.PricingProduct;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.models.ReferenceFare;
import com.voyra.crm.repository.AgencyPricingRepository;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Uses the real AgencyPricingService over mocked repositories so preview runs the real guard on the seeded basket. */
@WebMvcTest(PlatformAgencyPricingController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class, RestAuthenticationEntryPoint.class,
        AgencyPricingService.class})
class PlatformAgencyPricingControllerTest {

    private static final String BASE = "/api/platform/agencies/T1/pricing";

    @Autowired
    private MockMvc mockMvc;
    @MockBean
    private AgencyPricingRepository repository;
    @MockBean
    private TenantRepository tenantRepository;
    @MockBean
    private PlatformSettingService settings;
    @MockBean
    private PlatformAuditService auditService;

    private void seedBasket() {
        given(tenantRepository.existsById("T1")).willReturn(true);
        given(settings.discountBase()).willReturn(DiscountBase.GROSS);
        given(settings.commissionEstimatePct()).willReturn(new BigDecimal("5"));
        given(settings.referenceFareBasket()).willReturn(List.of(
                new ReferenceFare("Domestic LCC", new BigDecimal("5000"), new BigDecimal("3500")),
                new ReferenceFare("Domestic FSC", new BigDecimal("9000"), new BigDecimal("6000")),
                new ReferenceFare("International", new BigDecimal("45000"), new BigDecimal("22000"))));
    }

    @Test
    void previewFlagsALossMakingRateOnTheSeededBasket() throws Exception {
        seedBasket();
        mockMvc.perform(get(BASE + "/preview").param("product", "FLIGHT").param("discountPct", "10").param("commissionPct", "4")
                        .with(user("a").roles("SUPER_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requiresSecondApproval").value(true))
                .andExpect(jsonPath("$.worstCaseNetPer10k").value(-951.11))
                .andExpect(jsonPath("$.basketLines.length()").value(3));
    }

    private static RequestPostProcessor admin(String userId) {
        return authentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal(userId, "admin", UserType.SUPER_ADMIN, null), null,
                List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));
    }

    @Test
    void superAdminCanListCreateAndApprove() throws Exception {
        seedBasket();
        given(repository.findByTenantIdOrderByEffectiveFromDesc("T1")).willReturn(List.of());
        mockMvc.perform(get(BASE).with(admin("U1"))).andExpect(status().isOk());
        given(repository.save(any())).willAnswer(i -> i.getArgument(0));
        mockMvc.perform(post(BASE).with(admin("U1")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"product\":\"FLIGHT\",\"discountPct\":10,\"commissionPct\":4,\"effectiveFrom\":\"2099-01-01T00:00:00Z\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.requiresSecondApproval").value(true))
                .andExpect(jsonPath("$.createdBy").value("U1"))
                .andExpect(jsonPath("$.approvedAt").doesNotExist());
        mockMvc.perform(post(BASE).with(admin("U1")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"product\":\"FLIGHT\",\"discountPct\":101,\"commissionPct\":0,\"effectiveFrom\":\"2099-01-01T00:00:00Z\"}"))
                .andExpect(status().isBadRequest());

        AgencyPricing pending = AgencyPricing.builder().id("P1").tenantId("T1").product(PricingProduct.FLIGHT)
                .discountPct(BigDecimal.TEN).commissionPct(BigDecimal.ONE).effectiveFrom(Instant.now())
                .worstCaseNetPer10k(new BigDecimal("-951.11")).requiresSecondApproval(true)
                .createdBy("U1").createdAt(Instant.now()).build();
        given(repository.findById("P1")).willReturn(Optional.of(pending));
        mockMvc.perform(post(BASE + "/P1/approve").with(admin("U1"))).andExpect(status().isConflict());
        mockMvc.perform(post(BASE + "/P1/approve").with(admin("U2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvedBy").value("U2"));
    }

    @Test
    void agencyOwnerIsForbidden() throws Exception {
        mockMvc.perform(get(BASE).with(user("o").roles("AGENCY_OWNER"))).andExpect(status().isForbidden());
        mockMvc.perform(post(BASE + "/P1/approve").with(user("o").roles("AGENCY_OWNER"))).andExpect(status().isForbidden());
    }
}
