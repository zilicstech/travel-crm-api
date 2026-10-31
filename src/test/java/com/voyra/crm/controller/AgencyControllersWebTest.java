package com.voyra.crm.controller;

import com.voyra.crm.config.SecurityConfig;
import com.voyra.crm.dto.LoginResponse;
import com.voyra.crm.enums.AgencySettingKind;
import com.voyra.crm.enums.AuditEntityType;
import com.voyra.crm.enums.FlightSortOrder;
import com.voyra.crm.enums.MarkupMode;
import com.voyra.crm.enums.ServiceType;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.security.JwtAuthenticationFilter;
import com.voyra.crm.security.JwtService;
import com.voyra.crm.security.RestAuthenticationEntryPoint;
import com.voyra.crm.service.AgencyProfileService;
import com.voyra.crm.service.AgencySettingService;
import com.voyra.crm.service.AgentService;
import com.voyra.crm.service.AuditService;
import com.voyra.crm.service.AuthService;
import com.voyra.crm.service.DashboardService;
import com.voyra.crm.service.ExchangeRateService;
import com.voyra.crm.service.FlightSearchService;
import com.voyra.crm.service.MarkupDefaultService;
import com.voyra.crm.service.PublicProposalService;
import com.voyra.crm.service.ReportService;
import com.voyra.crm.service.VendorService;
import com.voyra.crm.service.WalletQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static com.voyra.crm.controller.WebTestSupport.as;
import static com.voyra.crm.controller.WebTestSupport.json;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Login, the agency's own settings, staff, vendors, dashboards, reports, wallet, flight search and the public proposal. */
@WebMvcTest({AuthController.class, AgencyProfileController.class, AgencySettingController.class, AgentDashboardController.class,
        OwnerDashboardController.class, AuditLogController.class, MarkupDefaultController.class, OwnerAgentController.class,
        VendorController.class, WalletController.class, FlightSearchController.class, PublicProposalController.class,
        ReportController.class, ExchangeRateController.class})
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class, RestAuthenticationEntryPoint.class})
class AgencyControllersWebTest {

    private static final String LOGIN = "{\"email\":\"owner@example.test\",\"password\":\"Secret-123\"}";
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Autowired private MockMvc mvc;
    @MockBean private AuthService authService;
    @MockBean private AgencyProfileService agencyProfileService;
    @MockBean private AgencySettingService agencySettingService;
    @MockBean private DashboardService dashboardService;
    @MockBean private AuditService auditService;
    @MockBean private MarkupDefaultService markupDefaultService;
    @MockBean private AgentService agentService;
    @MockBean private VendorService vendorService;
    @MockBean private WalletQueryService walletQueryService;
    @MockBean private FlightSearchService flightSearchService;
    @MockBean private PublicProposalService publicProposalService;
    @MockBean private ReportService reportService;
    @MockBean private ExchangeRateService exchangeRateService;

    @Test
    void everyLoginReturns200OnSuccessAnd401OnFailure() throws Exception {
        LoginResponse ok = LoginResponse.builder().success(true).build();
        LoginResponse no = LoginResponse.builder().success(false).message("Invalid email or password").build();
        given(authService.login(any())).willReturn(ok);
        given(authService.loginPlatformAdmin(any())).willReturn(no);
        given(authService.loginAgent(any())).willReturn(ok);

        mvc.perform(json(post("/api/auth/login"), LOGIN)).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        mvc.perform(json(post("/api/auth/login/platform-admin"), LOGIN))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value("Invalid email or password"));
        mvc.perform(json(post("/api/auth/login/agent"), LOGIN)).andExpect(status().isOk());
    }

    @Test
    void theAgencyProfileIsReadAndUpdatedByTheOwner() throws Exception {
        mvc.perform(get("/api/agency/profile").with(as(UserType.AGENCY_OWNER))).andExpect(status().isOk());
        mvc.perform(json(put("/api/agency/profile"), "{}").with(as(UserType.AGENCY_OWNER))).andExpect(status().isOk());
        mvc.perform(get("/api/agency/profile").with(as(UserType.AGENT))).andExpect(status().isForbidden());
        verify(agencyProfileService).getProfile();
        verify(agencyProfileService).updateProfile(any());
    }

    @Test
    void settingsAreReadByAgentsButChangedOnlyByTheOwner() throws Exception {
        String kind = AgencySettingKind.values()[0].name();
        mvc.perform(json(post("/api/agency/settings"), "{\"kind\":\"" + kind + "\",\"name\":\"Walk-in\"}").with(as(UserType.AGENCY_OWNER)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/agency/settings").param("kind", kind).param("serviceType", ServiceType.values()[0].name()).with(as(UserType.AGENT)))
                .andExpect(status().isOk());
        mvc.perform(json(patch("/api/agency/settings/S1"), "{\"name\":\"Referral\"}").with(as(UserType.AGENCY_OWNER))).andExpect(status().isOk());
        mvc.perform(delete("/api/agency/settings/S1").with(as(UserType.AGENCY_OWNER))).andExpect(status().isNoContent());
        mvc.perform(delete("/api/agency/settings/S1").with(as(UserType.AGENT))).andExpect(status().isForbidden());

        verify(agencySettingService).create(any());
        verify(agencySettingService).list(AgencySettingKind.values()[0], ServiceType.values()[0]);
        verify(agencySettingService).update(eq("S1"), any());
        verify(agencySettingService).delete("S1");
    }

    @Test
    void dashboardsAndTheAuditLog() throws Exception {
        mvc.perform(get("/api/dashboard/agent/summary").with(as(UserType.AGENT))).andExpect(status().isOk());
        mvc.perform(get("/api/dashboard/owner/summary").with(as(UserType.AGENCY_OWNER))).andExpect(status().isOk());
        mvc.perform(get("/api/dashboard/owner/summary").with(as(UserType.AGENT))).andExpect(status().isForbidden());
        mvc.perform(get("/api/audit-log").param("entityType", AuditEntityType.values()[0].name()).param("actorId", "U1")
                .param("from", "2026-04-01").with(as(UserType.AGENCY_OWNER))).andExpect(status().isOk());
        mvc.perform(get("/api/audit-log/" + AuditEntityType.values()[0] + "/E1").with(as(UserType.AGENCY_OWNER))).andExpect(status().isOk());

        verify(dashboardService).getAgentSummary();
        verify(dashboardService).getOwnerSummary();
        verify(auditService).search(eq(AuditEntityType.values()[0]), eq("U1"), eq(LocalDate.of(2026, 4, 1)), isNull(), argThat(p -> p.getPageNumber() == 0));
        verify(auditService).listForRecord(AuditEntityType.values()[0], "E1");
    }

    @Test
    void markupDefaultsAreSetByTheOwner() throws Exception {
        String type = ServiceType.values()[0].name();
        String body = "{\"mode\":\"" + MarkupMode.values()[0] + "\",\"value\":10}";
        mvc.perform(get("/api/agency/markup-defaults").with(as(UserType.AGENT))).andExpect(status().isOk());
        mvc.perform(json(put("/api/agency/markup-defaults/" + type), body).with(as(UserType.AGENCY_OWNER))).andExpect(status().isOk());
        mvc.perform(json(put("/api/agency/markup-defaults/" + type), body).with(as(UserType.AGENT))).andExpect(status().isForbidden());
        verify(markupDefaultService).list();
        verify(markupDefaultService).upsert(eq(ServiceType.values()[0]), any());
    }

    @Test
    void theOwnerManagesStaff() throws Exception {
        String pw = "{\"newPassword\":\"Secret-123\",\"confirmPassword\":\"Secret-123\"}";
        mvc.perform(json(post("/api/agents"), "{\"name\":\"Agent A\",\"email\":\"agent-a@example.test\",\"password\":\"Secret-123\"}")
                .with(as(UserType.AGENCY_OWNER))).andExpect(status().isOk());
        mvc.perform(get("/api/agents").with(as(UserType.AGENCY_OWNER))).andExpect(status().isOk());
        mvc.perform(get("/api/agents").param("role", "ACCOUNTANT").param("page", "0").param("size", "10").with(as(UserType.AGENCY_OWNER)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/agents/A1").with(as(UserType.AGENCY_OWNER))).andExpect(status().isOk());
        mvc.perform(get("/api/agents/teammates").with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());
        mvc.perform(json(put("/api/agents/A1"), "{\"name\":\"Agent B\"}").with(as(UserType.AGENCY_OWNER))).andExpect(status().isOk());
        mvc.perform(json(patch("/api/agents/A1/status"), "{\"isActive\":false}").with(as(UserType.AGENCY_OWNER))).andExpect(status().isOk());
        mvc.perform(delete("/api/agents/A1").with(as(UserType.AGENCY_OWNER))).andExpect(status().isNoContent());
        mvc.perform(get("/api/agents/A1/credentials").with(as(UserType.AGENCY_OWNER))).andExpect(status().isOk());
        mvc.perform(json(post("/api/agents/A1/reset-password"), pw).with(as(UserType.AGENCY_OWNER))).andExpect(status().isNoContent());

        verify(agentService).createAgent(any());
        verify(agentService).listAgents(UserType.AGENT);
        verify(agentService).listAgents(UserType.ACCOUNTANT, PageRequest.of(0, 10));
        verify(agentService).getAgent("A1");
        verify(agentService).listTeammates();
        verify(agentService).updateAgent(eq("A1"), any());
        verify(agentService).updateStatus("A1", false);
        verify(agentService).removeAgent("A1");
        verify(agentService).getCredentials("A1");
        verify(agentService).resetPassword(eq("A1"), any());
    }

    @Test
    void vendorsAreReadByStaffAndChangedByTheOwner() throws Exception {
        mvc.perform(get("/api/vendors/V1").with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());
        mvc.perform(json(patch("/api/vendors/V1"), "{\"name\":\"Air Co\"}").with(as(UserType.AGENCY_OWNER))).andExpect(status().isOk());
        mvc.perform(json(patch("/api/vendors/V1/status"), "{\"isActive\":false}").with(as(UserType.AGENCY_OWNER))).andExpect(status().isOk());
        mvc.perform(delete("/api/vendors/V1").with(as(UserType.AGENCY_OWNER))).andExpect(status().isNoContent());
        mvc.perform(delete("/api/vendors/V1").with(as(UserType.AGENT))).andExpect(status().isForbidden());

        verify(vendorService).get("V1");
        verify(vendorService).update(eq("V1"), any());
        verify(vendorService).updateStatus("V1", false);
        verify(vendorService).deactivate("V1");
    }

    @Test
    void walletEntriesArePagedForTheCallersOwnAgency() throws Exception {
        mvc.perform(get("/api/wallet/entries").param("page", "2").param("size", "10").with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());
        verify(walletQueryService).entries(WebTestSupport.TENANT, PageRequest.of(2, 10));
    }

    @Test
    void aPagedFlightSearchIsHandedToTheService() throws Exception {
        mvc.perform(json(post("/api/flights/search/paged"), "{\"query\":{\"origin\":\"DEL\",\"destination\":\"BOM\",\"departDate\":\"2026-12-01\",\"adults\":1},"
                + "\"page\":1,\"pageSize\":20,\"sort\":\"" + FlightSortOrder.values()[0] + "\"}").with(as(UserType.AGENT))).andExpect(status().isOk());
        verify(flightSearchService).searchPage(any());
    }

    @Test
    void aCustomerSavesTheirSelectionWithoutLoggingIn() throws Exception {
        mvc.perform(json(post("/api/public/proposals/tok-123/selection"), "{\"selectedItemIds\":[\"P1\",\"P2\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.message").value("Selection saved"));
        verify(publicProposalService).selectOptions("tok-123", List.of("P1", "P2"));
    }

    @Test
    void reportsChartsAndExports() throws Exception {
        given(reportService.exportLeadsCsv()).willReturn("a,b");
        given(reportService.exportBookingsCsv()).willReturn("c,d");
        given(reportService.exportRevenueCsv(3)).willReturn("e,f");
        given(reportService.exportAgentsCsv()).willReturn("g,h");
        given(reportService.exportLeadsXlsx()).willReturn(new byte[]{1});
        given(reportService.exportBookingsXlsx()).willReturn(new byte[]{2});
        given(reportService.exportRevenueXlsx(6)).willReturn(new byte[]{3});
        given(reportService.exportAgentsXlsx()).willReturn(new byte[]{4});
        given(reportService.exportLeadsPdf()).willReturn(new byte[]{5});
        given(reportService.exportBookingsPdf()).willReturn(new byte[]{6});
        given(reportService.exportRevenuePdf(6)).willReturn(new byte[]{7});
        given(reportService.exportAgentsPdf()).willReturn(new byte[]{8});
        String r = "/api/reports";

        mvc.perform(get(r + "/revenue-trend").with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());
        mvc.perform(get(r + "/agent-leaderboard").with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());
        mvc.perform(get(r + "/lead-pipeline").with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());
        mvc.perform(get(r + "/lead-source-distribution").with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());
        mvc.perform(get(r + "/booking-type-distribution").with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());
        mvc.perform(get(r + "/conversion-funnel").with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());
        for (String[] csv : new String[][]{{"leads", "a,b"}, {"bookings", "c,d"}, {"agents", "g,h"}}) {
            mvc.perform(get(r + "/export/" + csv[0] + ".csv").with(as(UserType.AGENCY_OWNER)))
                    .andExpect(content().contentType("text/csv;charset=UTF-8")).andExpect(content().string(csv[1]))
                    .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString(csv[0] + ".csv")));
        }
        mvc.perform(get(r + "/export/revenue.csv").param("months", "3").with(as(UserType.AGENCY_OWNER))).andExpect(content().string("e,f"));
        String[] names = {"leads", "bookings", "revenue", "agents"};
        for (int i = 0; i < names.length; i++) {
            mvc.perform(get(r + "/export/" + names[i] + ".xlsx").with(as(UserType.AGENCY_OWNER)))
                    .andExpect(content().contentType(XLSX)).andExpect(content().bytes(new byte[]{(byte) (i + 1)}))
                    .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString(names[i] + ".xlsx")));
            mvc.perform(get(r + "/export/" + names[i] + ".pdf").with(as(UserType.AGENCY_OWNER)))
                    .andExpect(content().contentType(MediaType.APPLICATION_PDF)).andExpect(content().bytes(new byte[]{(byte) (i + 5)}))
                    .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString(names[i] + ".pdf")));
        }

        verify(reportService).getRevenueTrend(6);
        verify(agentService).listAgents();
        verify(reportService).getLeadPipeline();
        verify(reportService).getLeadSourceDistribution();
        verify(reportService).getBookingTypeDistribution();
        verify(reportService).getConversionFunnel();
    }

    @Test
    void agentsGetNoReports() throws Exception {
        mvc.perform(get("/api/reports/revenue-trend").with(as(UserType.AGENT))).andExpect(status().isForbidden());
        verifyNoInteractions(reportService);
    }

    @Test
    void aPlatformAdminRecordsAnExchangeRate() throws Exception {
        String body = "{\"rateDate\":\"2026-10-30\",\"baseCurrency\":\"USD\",\"quoteCurrency\":\"INR\",\"rate\":83.1}";
        mvc.perform(json(post("/api/platform/exchange-rates"), body).with(as(UserType.SUPER_ADMIN))).andExpect(status().isOk());
        mvc.perform(json(post("/api/platform/exchange-rates"), body).with(as(UserType.AGENCY_OWNER))).andExpect(status().isForbidden());
        verify(exchangeRateService).record(any());
    }
}
