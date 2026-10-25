package com.voyra.crm.security;

import com.voyra.crm.cache.PlatformAdminCache;
import com.voyra.crm.cache.TenantCache;
import com.voyra.crm.config.SecurityConfig;
import com.voyra.crm.config.WebMvcConfig;
import com.voyra.crm.controller.ClientController;
import com.voyra.crm.controller.InvoiceDocumentController;
import com.voyra.crm.controller.OwnerAgentController;
import com.voyra.crm.controller.PlatformSettingController;
import com.voyra.crm.controller.ReportController;
import com.voyra.crm.controller.TopupController;
import com.voyra.crm.controller.VendorController;
import com.voyra.crm.enums.PlatformAuditAction;
import com.voyra.crm.enums.Sku;
import com.voyra.crm.enums.SubscriptionPlan;
import com.voyra.crm.enums.SubscriptionStatus;
import com.voyra.crm.enums.TenantStatus;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.models.Entitlements;
import com.voyra.crm.models.TaxComputationResult;
import com.voyra.crm.service.AgentService;
import com.voyra.crm.service.BaseCurrency;
import com.voyra.crm.service.InvoiceDocumentService;
import com.voyra.crm.service.InvoicePurchaseService;
import com.voyra.crm.service.TaxEngine;
import com.voyra.crm.service.ClientService;
import com.voyra.crm.service.EntitlementService;
import com.voyra.crm.service.PlatformAuditService;
import com.voyra.crm.service.PlatformSettingService;
import com.voyra.crm.service.ReportService;
import com.voyra.crm.service.TopupService;
import com.voyra.crm.service.VendorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** SKU x status matrix (spec §8): 402 SKU_REQUIRED / READ_ONLY, never-gated routes, SUPER_ADMIN bypass. */
@WebMvcTest({VendorController.class, ReportController.class, ClientController.class, TopupController.class,
        PlatformSettingController.class, OwnerAgentController.class, InvoiceDocumentController.class})
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class, RestAuthenticationEntryPoint.class,
        WebMvcConfig.class, TenantStatusInterceptor.class, EntitlementInterceptor.class})
class EntitlementInterceptorTest {

    private static final String TENANT = "TENT1";
    private static final String ADMIN = "ADM1";

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;
    @MockBean private EntitlementService entitlementService;
    @MockBean private PlatformAuditService platformAuditService;
    @MockBean private VendorService vendorService;
    @MockBean private ReportService reportService;
    @MockBean private AgentService agentService;
    @MockBean private ClientService clientService;
    @MockBean private TopupService topupService;
    @MockBean private PlatformSettingService platformSettingService;
    @MockBean private com.voyra.crm.service.PlatformSettingRequestService platformSettingRequestService;
    @MockBean private TaxEngine taxEngine;
    @MockBean private InvoiceDocumentService invoiceDocumentService;
    @MockBean private InvoicePurchaseService invoicePurchaseService;
    @MockBean private BaseCurrency baseCurrency;

    private String owner;

    @BeforeEach
    void setUp() {
        TenantCache.put(TENANT, "A", TenantStatus.ACTIVE);
        PlatformAdminCache.put(ADMIN, true);
        owner = "Bearer " + jwtService.generateToken("owner@a.com", UserType.AGENCY_OWNER, TENANT, TENANT);
    }

    @AfterEach
    void tearDown() {
        TenantCache.remove(TENANT);
    }

    private void entitled(Set<Sku> skus, boolean readOnly) {
        when(entitlementService.resolve(TENANT)).thenReturn(new Entitlements(
                skus.isEmpty() ? SubscriptionPlan.STARTER : SubscriptionPlan.CUSTOM,
                readOnly ? SubscriptionStatus.READ_ONLY : SubscriptionStatus.ACTIVE, skus, 0, readOnly, null));
    }

    private MockHttpServletRequestBuilder createVendor() {
        return post("/api/vendors").header("Authorization", owner)
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Air Co\",\"serviceTypes\":[\"FLIGHT\"]}");
    }

    private MockHttpServletRequestBuilder duplicateCheck() {
        return post("/api/clients/duplicate-check").header("Authorization", owner)
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Asha\"}");
    }

    @Test
    void entitledAgencyReadsAndWrites() throws Exception {
        entitled(EnumSet.allOf(Sku.class), false);
        mockMvc.perform(get("/api/vendors").header("Authorization", owner)).andExpect(status().isOk());
        mockMvc.perform(createVendor()).andExpect(status().isOk());
        mockMvc.perform(get("/api/reports/lead-pipeline").header("Authorization", owner)).andExpect(status().isOk());
    }

    @Test
    void starterAgencyGets402SkuRequiredNamingTheSku() throws Exception {
        entitled(Set.of(), false);
        mockMvc.perform(get("/api/vendors").header("Authorization", owner))
                .andExpect(status().is(402))
                .andExpect(jsonPath("$.code").value("SKU_REQUIRED"))
                .andExpect(jsonPath("$.sku").value("CRM_CORE"));
        mockMvc.perform(get("/api/reports/lead-pipeline").header("Authorization", owner))
                .andExpect(status().is(402))
                .andExpect(jsonPath("$.sku").value("REPORTS"));
        // A read-style POST is still a gated feature.
        mockMvc.perform(duplicateCheck()).andExpect(status().is(402)).andExpect(jsonPath("$.sku").value("CRM_CORE"));
    }

    @Test
    void eachSkuUnlocksOnlyItsOwnControllers() throws Exception {
        entitled(Set.of(Sku.REPORTS), false);
        mockMvc.perform(get("/api/reports/lead-pipeline").header("Authorization", owner)).andExpect(status().isOk());
        mockMvc.perform(get("/api/vendors").header("Authorization", owner))
                .andExpect(status().is(402)).andExpect(jsonPath("$.sku").value("CRM_CORE"));

        entitled(Set.of(Sku.CRM_CORE), false);
        mockMvc.perform(get("/api/vendors").header("Authorization", owner)).andExpect(status().isOk());
        mockMvc.perform(get("/api/reports/lead-pipeline").header("Authorization", owner))
                .andExpect(status().is(402)).andExpect(jsonPath("$.sku").value("REPORTS"));
    }

    @Test
    void neverGatedRoutesWorkOnStarter() throws Exception {
        entitled(Set.of(), false);
        mockMvc.perform(get("/api/wallet/topups").header("Authorization", owner)).andExpect(status().isOk());
        mockMvc.perform(post("/api/wallet/topups/T9/simulate").header("Authorization", owner)).andExpect(status().isOk());
    }

    @Test
    void readOnlyKeepsReadsAndTheReadStylePostsButRefusesWrites() throws Exception {
        entitled(EnumSet.allOf(Sku.class), true);
        mockMvc.perform(get("/api/vendors").header("Authorization", owner)).andExpect(status().isOk());
        mockMvc.perform(duplicateCheck()).andExpect(status().isOk());
        mockMvc.perform(createVendor())
                .andExpect(status().is(402))
                .andExpect(jsonPath("$.code").value("READ_ONLY"))
                .andExpect(jsonPath("$.sku").doesNotExist());
        verify(vendorService, never()).create(any());
    }

    /** SUSPENDED = READ_ONLY + wallet/booking writes refused, so READ_ONLY itself leaves never-gated writes alone. */
    @Test
    void readOnlyNeverTouchesNeverGatedRoutes() throws Exception {
        entitled(EnumSet.allOf(Sku.class), true);
        mockMvc.perform(post("/api/wallet/topups/T9/simulate").header("Authorization", owner)).andExpect(status().isOk());
    }

    @Test
    void readOnlyWithoutTheSkuSaysSkuRequired() throws Exception {
        entitled(Set.of(Sku.REPORTS), true);
        mockMvc.perform(createVendor()).andExpect(status().is(402)).andExpect(jsonPath("$.code").value("SKU_REQUIRED"));
    }

    @Test
    void suspendedKeepsIts423() throws Exception {
        TenantCache.put(TENANT, "A", TenantStatus.SUSPENDED);
        entitled(EnumSet.allOf(Sku.class), true);
        mockMvc.perform(createVendor()).andExpect(status().is(423)).andExpect(jsonPath("$.code").value("TENANT_SUSPENDED"));
    }

    @Test
    void superAdminActingOnATenantBypassesAndIsAudited() throws Exception {
        String admin = "Bearer " + jwtService.generateToken("root@voyra.in", UserType.SUPER_ADMIN, ADMIN, null);
        mockMvc.perform(get("/api/platform/settings").header("Authorization", admin).header("X-Tenant-Id", TENANT))
                .andExpect(status().isOk());
        verify(platformAuditService).record(eq(PlatformAuditAction.TENANT_ACT_AS), eq("TENANT"), eq(TENANT),
                eq(Map.of("method", "GET", "path", "/api/platform/settings")));
        verify(entitlementService, never()).resolve(anyString());
    }

    @Test
    void superAdminWithoutATenantIsNotAudited() throws Exception {
        String admin = "Bearer " + jwtService.generateToken("root@voyra.in", UserType.SUPER_ADMIN, ADMIN, null);
        mockMvc.perform(get("/api/platform/settings").header("Authorization", admin)).andExpect(status().isOk());
        verify(platformAuditService, never()).record(any(), anyString(), anyString(), anyMap());
    }

    @Test
    void readOnlyRefusesPutPatchAndDelete() throws Exception {
        entitled(EnumSet.allOf(Sku.class), true);
        for (MockHttpServletRequestBuilder write : java.util.List.of(
                put("/api/accounts/invoices/I1"), patch("/api/vendors/V1"), patch("/api/vendors/V1/status"),
                delete("/api/vendors/V1"), delete("/api/accounts/invoices/I1"))) {
            mockMvc.perform(write.header("Authorization", owner).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().is(402)).andExpect(jsonPath("$.code").value("READ_ONLY"));
        }
    }

    @Test
    void readOnlyAllowsTheTaxPreviewPost() throws Exception {
        entitled(EnumSet.allOf(Sku.class), true);
        when(taxEngine.compute(any())).thenReturn(mock(TaxComputationResult.class));
        mockMvc.perform(post("/api/accounts/invoices/preview-tax").header("Authorization", owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientId\":\"C1\",\"taxableAmount\":1000,\"supplyNature\":\"AIR_TICKET\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void staffManagementIsNeverGatedBySku() throws Exception {
        entitled(Set.of(), false);
        mockMvc.perform(get("/api/agents").header("Authorization", owner)).andExpect(status().isOk());
    }

    /** An owner can always cut staff off; every other staff write waits for a writable subscription. */
    @Test
    void readOnlyOwnerCanDeactivateAndDeleteStaffButNothingElse() throws Exception {
        entitled(EnumSet.allOf(Sku.class), true);
        mockMvc.perform(patch("/api/agents/A1/status").header("Authorization", owner)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"isActive\":false}"))
                .andExpect(status().isOk());
        verify(agentService).updateStatus("A1", false);
        mockMvc.perform(delete("/api/agents/A1").header("Authorization", owner)).andExpect(status().isNoContent());
        verify(agentService).removeAgent("A1");

        mockMvc.perform(post("/api/agents").header("Authorization", owner).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().is(402)).andExpect(jsonPath("$.code").value("READ_ONLY"));
        mockMvc.perform(put("/api/agents/A1").header("Authorization", owner).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().is(402)).andExpect(jsonPath("$.code").value("READ_ONLY"));
        mockMvc.perform(post("/api/agents/A1/reset-password").header("Authorization", owner)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().is(402)).andExpect(jsonPath("$.code").value("READ_ONLY"));
    }
}
