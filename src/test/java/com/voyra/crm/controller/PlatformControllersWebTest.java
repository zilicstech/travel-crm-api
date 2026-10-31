package com.voyra.crm.controller;

import com.voyra.crm.config.SecurityConfig;
import com.voyra.crm.enums.KycDocumentStatus;
import com.voyra.crm.enums.PricingProduct;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.security.JwtAuthenticationFilter;
import com.voyra.crm.security.JwtService;
import com.voyra.crm.security.RestAuthenticationEntryPoint;
import com.voyra.crm.service.AgencyApplicationService;
import com.voyra.crm.service.AgencyPricingService;
import com.voyra.crm.service.AgencyService;
import com.voyra.crm.service.InquiryService;
import com.voyra.crm.service.PlatformSupplierCredentialService;
import com.voyra.crm.service.PlatformSupplierTestService;
import com.voyra.crm.service.TopupService;
import com.voyra.crm.service.WalletQueryService;
import com.voyra.crm.service.booking.PlatformBookingQueryService;
import com.voyra.crm.service.booking.PlatformBookingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static com.voyra.crm.controller.WebTestSupport.as;
import static com.voyra.crm.controller.WebTestSupport.json;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The platform admin's agency, application, pricing, credential, top-up, wallet and booking endpoints. */
@WebMvcTest({PlatformAdminBookingController.class, PlatformAgencyApplicationController.class, PlatformAgencyController.class,
        PlatformAgencyPricingController.class, PlatformInquiryController.class, PlatformPricingRuleController.class,
        PlatformSupplierCredentialController.class, PlatformTopupController.class, PlatformWalletController.class})
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class, RestAuthenticationEntryPoint.class})
class PlatformControllersWebTest {

    private static final String ADMIN_ID = "U-SUPER_ADMIN";

    @Autowired private MockMvc mvc;
    @MockBean private PlatformBookingService platformBookingService;
    @MockBean private PlatformBookingQueryService platformBookingQueryService;
    @MockBean private AgencyApplicationService agencyApplicationService;
    @MockBean private AgencyService agencyService;
    @MockBean private AgencyPricingService agencyPricingService;
    @MockBean private InquiryService inquiryService;
    @MockBean private PlatformSupplierCredentialService credentialService;
    @MockBean private PlatformSupplierTestService supplierTestService;
    @MockBean private TopupService topupService;
    @MockBean private WalletQueryService walletQueryService;

    @Test
    void agenciesAreCreatedListedUpdatedAndTheirOwnerPasswordReset() throws Exception {
        String base = "/api/platform/agencies";
        mvc.perform(json(post(base), "{\"agencyName\":\"Fake Travels\",\"ownerName\":\"Owner A\",\"ownerEmail\":\"owner-a@example.test\","
                + "\"ownerPassword\":\"Secret-123\",\"baseCurrencyCode\":\"INR\"}").with(as(UserType.SUPER_ADMIN))).andExpect(status().isOk());
        mvc.perform(get(base).with(as(UserType.SUPER_ADMIN))).andExpect(status().isOk());
        mvc.perform(get(base + "/T9").with(as(UserType.SUPER_ADMIN))).andExpect(status().isOk());
        mvc.perform(get(base + "/T9/credentials").with(as(UserType.SUPER_ADMIN))).andExpect(status().isOk());
        mvc.perform(json(patch(base + "/T9"), "{\"agencyName\":\"Renamed\"}").with(as(UserType.SUPER_ADMIN))).andExpect(status().isOk());
        mvc.perform(json(post(base + "/T9/reset-password"), "{\"newPassword\":\"Secret-456\",\"confirmPassword\":\"Secret-456\"}")
                .with(as(UserType.SUPER_ADMIN))).andExpect(status().isNoContent());

        verify(agencyService).createAgency(any());
        verify(agencyService).listAgencies();
        verify(agencyService).getAgency("T9");
        verify(agencyService).getCredentials("T9");
        verify(agencyService).updateAgency(eq("T9"), any());
        verify(agencyService).resetOwnerPassword(eq("T9"), any());
    }

    @Test
    void agencyOwnersCannotReachThePlatformApi() throws Exception {
        mvc.perform(get("/api/platform/agencies").with(as(UserType.AGENCY_OWNER))).andExpect(status().isForbidden());
        mvc.perform(get("/api/platform/pricing-rules").with(as(UserType.AGENCY_OWNER))).andExpect(status().isForbidden());
        mvc.perform(get("/api/platform/wallets/T1/entries").with(as(UserType.AGENCY_OWNER))).andExpect(status().isForbidden());
        verifyNoInteractions(agencyService, agencyPricingService, walletQueryService);
    }

    @Test
    void applicationsArePagedReadAndTheirDocumentsDecided() throws Exception {
        String base = "/api/platform/agency-applications";
        mvc.perform(get(base).param("page", "3").param("size", "10").with(as(UserType.SUPER_ADMIN))).andExpect(status().isOk());
        mvc.perform(get(base + "/APP1").with(as(UserType.SUPER_ADMIN))).andExpect(status().isOk());
        mvc.perform(json(patch(base + "/APP1/documents/D1"), "{\"status\":\"" + KycDocumentStatus.VERIFIED + "\"}").with(as(UserType.SUPER_ADMIN)))
                .andExpect(status().isOk());

        verify(agencyApplicationService).list(isNull(), eq(PageRequest.of(3, 10)));
        verify(agencyApplicationService).get("APP1");
        verify(agencyApplicationService).decideDocument(eq("APP1"), eq("D1"), any());
    }

    @Test
    void pricingRulesAndAssignments() throws Exception {
        String product = PricingProduct.values()[0].name();
        mvc.perform(get("/api/platform/pricing-rules").param("product", product).with(as(UserType.SUPER_ADMIN))).andExpect(status().isOk());
        mvc.perform(json(put("/api/platform/pricing-rules/R1"), "{\"name\":\"Discount 5%\",\"pct\":5}").with(as(UserType.SUPER_ADMIN)))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/platform/pricing-rules/R1").with(as(UserType.SUPER_ADMIN))).andExpect(status().isNoContent());
        mvc.perform(get("/api/platform/pricing-rules/R1/impact").param("pct", "7.5").with(as(UserType.SUPER_ADMIN))).andExpect(status().isOk());
        mvc.perform(json(put("/api/platform/agencies/T9/pricing/" + product), "{\"discountRuleId\":\"D1\",\"commissionRuleId\":\"C1\"}")
                .with(as(UserType.SUPER_ADMIN))).andExpect(status().isOk());

        verify(agencyPricingService).listRules(PricingProduct.values()[0]);
        verify(agencyPricingService).updateRule(eq("R1"), any());
        verify(agencyPricingService).deleteRule("R1");
        verify(agencyPricingService).impact("R1", new BigDecimal("7.5"), false);
        verify(agencyPricingService).assign(eq("T9"), eq(PricingProduct.values()[0]), any());
    }

    @Test
    void inquiriesWalletsCredentialsTopupsAndBookings() throws Exception {
        mvc.perform(get("/api/platform/inquiries").param("search", "goa").param("page", "1").with(as(UserType.SUPER_ADMIN))).andExpect(status().isOk());
        mvc.perform(get("/api/platform/wallets/T9/entries").with(as(UserType.SUPER_ADMIN))).andExpect(status().isOk());
        mvc.perform(get("/api/platform/wallets/T9/entries").param("page", "2").param("size", "5").with(as(UserType.SUPER_ADMIN))).andExpect(status().isOk());
        mvc.perform(get("/api/platform/supplier-credentials/K1").with(as(UserType.SUPER_ADMIN))).andExpect(status().isOk());
        mvc.perform(json(post("/api/platform/topups/TP1/reject"), "{\"note\":\"UTR not found\"}").with(as(UserType.SUPER_ADMIN))).andExpect(status().isOk());
        mvc.perform(get("/api/platform/bookings/PB1").with(as(UserType.SUPER_ADMIN))).andExpect(status().isOk());

        verify(inquiryService).list(eq("goa"), argThat(p -> p.getPageNumber() == 1));
        verify(walletQueryService).entries(eq("T9"), argThat(p -> p.getPageNumber() == 0));
        verify(walletQueryService).entries("T9", PageRequest.of(2, 5));
        verify(credentialService).get("K1");
        verify(topupService).reject(eq("TP1"), eq(ADMIN_ID), any());
        verify(platformBookingQueryService).adminGet("PB1");
    }
}
