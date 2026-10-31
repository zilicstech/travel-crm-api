package com.voyra.crm.controller;

import com.voyra.crm.config.SecurityConfig;
import com.voyra.crm.enums.ClientType;
import com.voyra.crm.enums.CommunicationChannel;
import com.voyra.crm.enums.CommunicationDirection;
import com.voyra.crm.enums.FollowUpStatus;
import com.voyra.crm.enums.LeadStatus;
import com.voyra.crm.enums.MemberRelation;
import com.voyra.crm.enums.ProposalItemType;
import com.voyra.crm.enums.ServiceStatus;
import com.voyra.crm.enums.ServiceType;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.security.JwtAuthenticationFilter;
import com.voyra.crm.security.JwtService;
import com.voyra.crm.security.RestAuthenticationEntryPoint;
import com.voyra.crm.service.CalendarService;
import com.voyra.crm.service.ClientService;
import com.voyra.crm.service.CommunicationLogService;
import com.voyra.crm.service.EscalationService;
import com.voyra.crm.service.FeedbackService;
import com.voyra.crm.service.LeadFollowUpService;
import com.voyra.crm.service.LeadService;
import com.voyra.crm.service.LeadTimelineService;
import com.voyra.crm.service.MemberService;
import com.voyra.crm.service.ProposalLinkService;
import com.voyra.crm.service.ServiceInstanceService;
import com.voyra.crm.service.ShiftHandoverService;
import com.voyra.crm.service.VisaService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
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

import static com.voyra.crm.controller.WebTestSupport.FILE;
import static com.voyra.crm.controller.WebTestSupport.as;
import static com.voyra.crm.controller.WebTestSupport.bytes;
import static com.voyra.crm.controller.WebTestSupport.json;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Clients, members, leads, services, follow-ups, visas and the agent boards (owner and agent). */
@WebMvcTest({ClientController.class, ClientSubResourceController.class, CommunicationLogController.class, FeedbackController.class,
        LeadController.class, LeadFollowUpController.class, LeadServiceController.class, FollowUpBoardController.class,
        ServiceBoardController.class, EscalationController.class, CalendarController.class, VisaController.class, ShiftHandoverController.class})
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class, RestAuthenticationEntryPoint.class})
class CrmControllersWebTest {

    @Autowired private MockMvc mvc;
    @MockBean private ClientService clientService;
    @MockBean private MemberService memberService;
    @MockBean private CommunicationLogService communicationLogService;
    @MockBean private FeedbackService feedbackService;
    @MockBean private LeadService leadService;
    @MockBean private LeadTimelineService leadTimelineService;
    @MockBean private ProposalLinkService proposalLinkService;
    @MockBean private LeadFollowUpService leadFollowUpService;
    @MockBean private ServiceInstanceService serviceInstanceService;
    @MockBean private EscalationService escalationService;
    @MockBean private CalendarService calendarService;
    @MockBean private VisaService visaService;
    @MockBean private ShiftHandoverService shiftHandoverService;

    @Test
    void clientsAreCreatedLookedUpUpdatedAndToggled() throws Exception {
        String create = "{\"identifier\":\"9800000000\",\"name\":\"Fake Family\",\"type\":\"" + ClientType.values()[0]
                + "\",\"primaryMemberName\":\"Pax A\"}";
        mvc.perform(json(post("/api/clients"), create).with(as(UserType.AGENT))).andExpect(status().isOk());
        mvc.perform(get("/api/clients").with(as(UserType.AGENT))).andExpect(status().isOk());
        mvc.perform(get("/api/clients").param("type", ClientType.values()[0].name()).param("page", "2").param("size", "5").with(as(UserType.AGENT)))
                .andExpect(status().isOk());
        mvc.perform(json(post("/api/clients/duplicate-check"), "{\"name\":\"Fake\"}").with(as(UserType.AGENT))).andExpect(status().isOk());
        mvc.perform(get("/api/clients/lookup").param("identifier", "9800000000").with(as(UserType.AGENT))).andExpect(status().isOk());
        mvc.perform(get("/api/clients/C1").with(as(UserType.AGENT))).andExpect(status().isOk());
        mvc.perform(get("/api/clients/C1/credit").with(as(UserType.AGENT))).andExpect(status().isOk());
        mvc.perform(json(put("/api/clients/C1"), "{\"name\":\"Renamed\"}").with(as(UserType.AGENT))).andExpect(status().isOk());
        mvc.perform(json(patch("/api/clients/C1/status"), "{\"isActive\":false}").with(as(UserType.AGENCY_OWNER))).andExpect(status().isOk());

        verify(clientService).createClient(any());
        verify(clientService).listClients(null);
        verify(clientService).listClients(ClientType.values()[0], PageRequest.of(2, 5));
        verify(clientService).checkDuplicates(any());
        verify(clientService).lookupByIdentifier("9800000000");
        verify(clientService).getClient("C1");
        verify(clientService).getCredit("C1");
        verify(clientService).updateClient(eq("C1"), any());
        verify(clientService).updateStatus("C1", false);
    }

    @Test
    void anAccountantHasNoClientWorkspace() throws Exception {
        mvc.perform(get("/api/clients/C1/members").with(as(UserType.ACCOUNTANT))).andExpect(status().isForbidden());
        mvc.perform(get("/api/leads").with(as(UserType.ACCOUNTANT))).andExpect(status().isForbidden());
        mvc.perform(get("/api/visas").with(as(UserType.ACCOUNTANT))).andExpect(status().isForbidden());
        verifyNoInteractions(memberService, leadService, visaService);
    }

    @Test
    void membersAndTheirDocuments() throws Exception {
        String base = "/api/clients/C1/members";
        given(memberService.downloadDocument("C1", "M1", "D1")).willReturn(new MemberService.DocumentContent(bytes("p"), "pass.jpg", "image/jpeg"));
        given(memberService.downloadDocument("C1", "M1", "D2")).willReturn(new MemberService.DocumentContent(bytes("q"), "pass.bin", null));

        mvc.perform(get(base).with(as(UserType.AGENT))).andExpect(status().isOk());
        mvc.perform(json(post(base), "{\"name\":\"Pax B\",\"relation\":\"" + MemberRelation.values()[0] + "\"}").with(as(UserType.AGENT)))
                .andExpect(status().isOk());
        mvc.perform(json(put(base + "/M1"), "{\"name\":\"Pax C\"}").with(as(UserType.AGENT))).andExpect(status().isOk());
        mvc.perform(json(patch(base + "/M1/status"), "{\"isActive\":true}").with(as(UserType.AGENT))).andExpect(status().isOk());
        mvc.perform(multipart(base + "/M1/documents").file(FILE).param("docType", "PASSPORT").with(as(UserType.AGENT))).andExpect(status().isOk());
        mvc.perform(delete(base + "/M1/documents/D1").with(as(UserType.AGENT))).andExpect(status().isNoContent());
        mvc.perform(get(base + "/M1/documents/D1/download").with(as(UserType.AGENT)))
                .andExpect(content().contentType(MediaType.IMAGE_JPEG))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"pass.jpg\""));
        mvc.perform(get(base + "/M1/documents/D2/download").with(as(UserType.AGENT))).andExpect(content().contentType(MediaType.APPLICATION_OCTET_STREAM));

        verify(memberService).listMembers("C1");
        verify(memberService).addMember(eq("C1"), any());
        verify(memberService).updateMember(eq("C1"), eq("M1"), any());
        verify(memberService).updateStatus("C1", "M1", true);
        verify(memberService).uploadDocument(eq("C1"), eq("M1"), eq("PASSPORT"), any());
        verify(memberService).deleteDocument("C1", "M1", "D1");
    }

    @Test
    void communicationsAndFeedbackOfAClient() throws Exception {
        String base = "/api/clients/C1/communications";
        given(communicationLogService.downloadFile("C1", "L1")).willReturn(new CommunicationLogService.CommunicationFileContent(bytes("a"), "call.mp3", "audio/mpeg"));
        given(communicationLogService.downloadFile("C1", "L2")).willReturn(new CommunicationLogService.CommunicationFileContent(bytes("b"), "x.bin", null));

        mvc.perform(json(post(base), "{\"channel\":\"" + CommunicationChannel.CALL + "\",\"direction\":\"" + CommunicationDirection.INBOUND
                + "\",\"summary\":\"Asked for a quote\"}").with(as(UserType.AGENT))).andExpect(status().isOk());
        mvc.perform(get(base).with(as(UserType.AGENT))).andExpect(status().isOk());
        mvc.perform(delete(base + "/L1").with(as(UserType.AGENT))).andExpect(status().isNoContent());
        mvc.perform(multipart(base + "/L1/file").file(FILE).with(as(UserType.AGENT))).andExpect(status().isOk());
        mvc.perform(get(base + "/L1/file").with(as(UserType.AGENT)))
                .andExpect(content().contentType("audio/mpeg")).andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"call.mp3\""));
        mvc.perform(get(base + "/L2/file").with(as(UserType.AGENT))).andExpect(content().contentType(MediaType.APPLICATION_OCTET_STREAM));
        mvc.perform(get("/api/clients/C1/feedback").with(as(UserType.AGENT))).andExpect(status().isOk());

        verify(communicationLogService).create(eq("C1"), any());
        verify(communicationLogService).listForClient("C1");
        verify(communicationLogService).delete("C1", "L1");
        verify(communicationLogService).attachFile(eq("C1"), eq("L1"), any());
        verify(feedbackService).listForClient("C1");
    }

    @Test
    void leadsAreCreatedUpdatedAndWorkedThrough() throws Exception {
        String item = "{\"type\":\"" + ProposalItemType.values()[0] + "\",\"description\":\"Hotel 3N\"}";
        mvc.perform(json(post("/api/leads"), "{\"clientId\":\"C1\",\"destination\":\"Goa\",\"categories\":[\"Holiday\"]}").with(as(UserType.AGENT)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/leads").param("status", LeadStatus.values()[0].name()).param("page", "0").with(as(UserType.AGENT))).andExpect(status().isOk());
        mvc.perform(get("/api/leads/L1").with(as(UserType.AGENT))).andExpect(status().isOk());
        mvc.perform(json(put("/api/leads/L1"), "{\"budget\":\"1L\"}").with(as(UserType.AGENT))).andExpect(status().isOk());
        mvc.perform(json(patch("/api/leads/L1/status"), "{\"status\":\"" + LeadStatus.values()[0] + "\"}").with(as(UserType.AGENT))).andExpect(status().isOk());
        mvc.perform(json(patch("/api/leads/L1/escalate"), "{\"escalated\":true,\"reason\":\"VIP\"}").with(as(UserType.AGENT))).andExpect(status().isOk());
        mvc.perform(json(patch("/api/leads/L1/proposal-lock"), "{\"locked\":true}").with(as(UserType.AGENT))).andExpect(status().isOk());
        mvc.perform(json(post("/api/leads/L1/notes"), "{\"text\":\"Called back\"}").with(as(UserType.AGENT))).andExpect(status().isOk());
        mvc.perform(json(post("/api/leads/L1/proposal-items"), item).with(as(UserType.AGENT))).andExpect(status().isOk());
        mvc.perform(json(post("/api/leads/L1/proposal-items/batch"), "{\"items\":[" + item + "]}").with(as(UserType.AGENT))).andExpect(status().isOk());
        mvc.perform(json(put("/api/leads/L1/proposal-items/P1"), "{}").with(as(UserType.AGENT))).andExpect(status().isOk());
        mvc.perform(patch("/api/leads/L1/proposal-items/P1/select").with(as(UserType.AGENT))).andExpect(status().isOk());
        mvc.perform(delete("/api/leads/L1/proposal-items/P1").with(as(UserType.AGENT))).andExpect(status().isNoContent());
        mvc.perform(get("/api/leads/L1/members").with(as(UserType.AGENT))).andExpect(status().isOk());
        mvc.perform(json(post("/api/leads/L1/members"), "{}").with(as(UserType.AGENT))).andExpect(status().isOk());
        mvc.perform(json(patch("/api/leads/L1/members/M1"), "{}").with(as(UserType.AGENT))).andExpect(status().isOk());
        mvc.perform(post("/api/leads/L1/proposal-link").with(as(UserType.AGENT))).andExpect(status().isOk());

        verify(leadService).createLead(any());
        verify(leadService).listLeads(LeadStatus.values()[0], PageRequest.of(0, 25));
        verify(leadService).updateDetails(eq("L1"), any());
        verify(leadService).updateStatus(eq("L1"), any());
        verify(leadService).setEscalated("L1", true, "VIP");
        verify(leadService).setProposalLocked("L1", true);
        verify(leadService).addNote(eq("L1"), any());
        verify(leadService).addProposalItem(eq("L1"), any());
        verify(leadService).addProposalItemsBatch(eq("L1"), any());
        verify(leadService).updateProposalItem(eq("L1"), eq("P1"), any());
        verify(leadService).selectProposalItem("L1", "P1");
        verify(leadService).removeProposalItem("L1", "P1");
        verify(leadService).listMembers("L1");
        verify(leadService).addMembers(eq("L1"), any());
        verify(leadService).updateMember(eq("L1"), eq("M1"), any());
        verify(proposalLinkService).generateLink("L1");
    }

    @Test
    void aLeadsCommunicationsAndTimelineAreReadOnlyAfterTheLeadIsCheckedVisible() throws Exception {
        mvc.perform(get("/api/leads").with(as(UserType.AGENT))).andExpect(status().isOk());
        mvc.perform(get("/api/leads/L1/communications").with(as(UserType.AGENT))).andExpect(status().isOk());
        mvc.perform(get("/api/leads/L1/timeline").with(as(UserType.AGENT))).andExpect(status().isOk());

        verify(leadService).listLeads(null);
        InOrder order = inOrder(leadService, communicationLogService, leadTimelineService);
        order.verify(leadService).getLead("L1");
        order.verify(communicationLogService).listForLead("L1");
        order.verify(leadService).getLead("L1");
        order.verify(leadTimelineService).listForLead("L1");
    }

    @Test
    void followUpsPerLeadAndOnTheBoard() throws Exception {
        String base = "/api/leads/L1/follow-ups";
        mvc.perform(json(post(base), "{\"dueDate\":\"2026-11-02\",\"note\":\"Call\",\"assignedAgentId\":\"A1\"}").with(as(UserType.AGENT)))
                .andExpect(status().isOk());
        mvc.perform(get(base).with(as(UserType.AGENT))).andExpect(status().isOk());
        mvc.perform(patch(base + "/F1/complete").with(as(UserType.AGENT))).andExpect(status().isOk());
        mvc.perform(delete(base + "/F1").with(as(UserType.AGENT))).andExpect(status().isNoContent());
        mvc.perform(get("/api/follow-ups").param("agentId", "A1").param("status", FollowUpStatus.values()[0].name())
                .param("dueBefore", "2026-11-05").with(as(UserType.AGENCY_OWNER))).andExpect(status().isOk());

        verify(leadFollowUpService).addFollowUp(eq("L1"), any());
        verify(leadFollowUpService).listForLead("L1");
        verify(leadFollowUpService).completeFollowUp("L1", "F1");
        verify(leadFollowUpService).deleteFollowUp("L1", "F1");
        verify(leadFollowUpService).board("A1", FollowUpStatus.values()[0], LocalDate.of(2026, 11, 5));
    }

    @Test
    void servicesOfALeadAndTheServiceBoard() throws Exception {
        String base = "/api/leads/L1/services";
        String draft = "{\"type\":\"" + ServiceType.values()[0] + "\"}";
        mvc.perform(json(post(base), "[" + draft + "]").with(as(UserType.AGENT))).andExpect(status().isOk());
        mvc.perform(get(base).with(as(UserType.AGENT))).andExpect(status().isOk());
        mvc.perform(json(put(base + "/S1"), draft).with(as(UserType.AGENT))).andExpect(status().isOk());
        mvc.perform(json(patch(base + "/S1/status"), "{\"status\":\"" + ServiceStatus.values()[0] + "\"}").with(as(UserType.AGENT))).andExpect(status().isOk());
        mvc.perform(post(base + "/S1/accept").with(as(UserType.AGENT))).andExpect(status().isOk());
        mvc.perform(json(patch(base + "/S1/assign"), "{\"agentId\":\"A2\"}").with(as(UserType.AGENCY_OWNER))).andExpect(status().isOk());
        mvc.perform(json(patch(base + "/S1/assign"), "{\"agentId\":\"A2\"}").with(as(UserType.AGENT))).andExpect(status().isForbidden());
        mvc.perform(post(base + "/S1/release").with(as(UserType.AGENT))).andExpect(status().isOk());
        mvc.perform(json(patch(base + "/S1/preferences"), "{\"name\":\"Window\",\"on\":true}").with(as(UserType.AGENT))).andExpect(status().isOk());
        mvc.perform(json(patch(base + "/S1/visa-checklist"), "{\"memberId\":\"M1\",\"key\":\"photo\",\"value\":true}").with(as(UserType.AGENT)))
                .andExpect(status().isOk());
        mvc.perform(delete(base + "/S1").with(as(UserType.AGENT))).andExpect(status().isNoContent());
        mvc.perform(get("/api/services").param("type", ServiceType.values()[0].name()).with(as(UserType.AGENT))).andExpect(status().isOk());

        verify(serviceInstanceService).createServices(eq("L1"), anyList());
        verify(serviceInstanceService).listForLead("L1");
        verify(serviceInstanceService).updateService(eq("L1"), eq("S1"), any());
        verify(serviceInstanceService).setStatus(eq("L1"), eq("S1"), any());
        verify(serviceInstanceService).acceptService("L1", "S1");
        verify(serviceInstanceService).assignService(eq("L1"), eq("S1"), any());
        verify(serviceInstanceService).releaseService("L1", "S1");
        verify(serviceInstanceService).togglePreference(eq("L1"), eq("S1"), any());
        verify(serviceInstanceService).toggleVisaChecklist(eq("L1"), eq("S1"), any());
        verify(serviceInstanceService).deleteService("L1", "S1");
        verify(serviceInstanceService).board(ServiceType.values()[0], null);
    }

    @Test
    void escalationsCalendarHandoversAndVisas() throws Exception {
        mvc.perform(get("/api/escalations").with(as(UserType.AGENT))).andExpect(status().isOk());
        mvc.perform(get("/api/calendar").param("from", "2026-11-01").param("to", "2026-11-30").with(as(UserType.AGENT))).andExpect(status().isOk());
        mvc.perform(json(post("/api/handovers"), "{\"summary\":\"Two follow-ups open\"}").with(as(UserType.AGENT))).andExpect(status().isOk());
        mvc.perform(get("/api/handovers").with(as(UserType.AGENT))).andExpect(status().isOk());
        mvc.perform(patch("/api/handovers/H1/acknowledge").with(as(UserType.AGENT))).andExpect(status().isOk());
        mvc.perform(json(post("/api/visas"), "{\"clientId\":\"C1\",\"country\":\"France\",\"visaType\":\"Tourist\"}").with(as(UserType.AGENT)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/visas").with(as(UserType.AGENT))).andExpect(status().isOk());
        mvc.perform(get("/api/visas").param("page", "1").with(as(UserType.AGENT))).andExpect(status().isOk());
        mvc.perform(get("/api/visas/V1").with(as(UserType.AGENT))).andExpect(status().isOk());
        mvc.perform(json(patch("/api/visas/V1/checklist"), "{}").with(as(UserType.AGENT))).andExpect(status().isOk());
        mvc.perform(get("/api/visas/dashboard-summary").with(as(UserType.AGENT))).andExpect(status().isOk());

        verify(escalationService).list();
        verify(calendarService).list(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30));
        verify(shiftHandoverService).create(any());
        verify(shiftHandoverService).list();
        verify(shiftHandoverService).acknowledge("H1");
        verify(visaService).createVisa(any());
        verify(visaService).listVisas();
        verify(visaService).listVisas(PageRequest.of(1, 25));
        verify(visaService).getVisa("V1");
        verify(visaService).updateChecklist(eq("V1"), any());
        verify(visaService).getDashboardSummary();
    }
}
