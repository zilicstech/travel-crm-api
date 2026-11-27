package com.voyra.crm.controller;

import com.voyra.crm.config.SecurityConfig;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.security.JwtAuthenticationFilter;
import com.voyra.crm.security.JwtService;
import com.voyra.crm.security.RestAuthenticationEntryPoint;
import com.voyra.crm.service.CancellationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static com.voyra.crm.controller.WebTestSupport.as;
import static com.voyra.crm.controller.WebTestSupport.json;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CancellationController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class, RestAuthenticationEntryPoint.class})
class CancellationControllerTest {

    @Autowired private MockMvc mvc;
    @MockBean private CancellationService cancellationService;

    @Test
    void ownerAgentAndAccountantReadTheTab() throws Exception {
        mvc.perform(get("/api/cancellations").with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());
        mvc.perform(get("/api/cancellations").param("view", "DUE").param("q", "ajay").param("assignedTo", "A1")
                .param("page", "1").param("size", "5").with(as(UserType.AGENT))).andExpect(status().isOk());
        mvc.perform(get("/api/cancellations/B1").with(as(UserType.AGENCY_OWNER))).andExpect(status().isOk());

        verify(cancellationService).list(CancellationService.View.OPEN, null, null, PageRequest.of(0, 20));
        verify(cancellationService).list(CancellationService.View.DUE, "ajay", "A1", PageRequest.of(1, 5));
        verify(cancellationService).detail("B1");
    }

    @Test
    void everyRoleMarksARefundProcessedOrReopensIt() throws Exception {
        mvc.perform(post("/api/cancellations/B1/refund-processed").with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/cancellations/B1/refund-processed")
                .with(as(UserType.AGENT))).andExpect(status().isOk());
        org.mockito.Mockito.verify(cancellationService).setRefundProcessed("B1", true);
        org.mockito.Mockito.verify(cancellationService).setRefundProcessed("B1", false);
    }

    @Test
    void ownerAndAgentWriteFollowUps() throws Exception {
        mvc.perform(json(post("/api/cancellations/B1/follow-ups"), "{\"kind\":\"SCHEDULED_CANCEL\",\"inDays\":2}").with(as(UserType.AGENT)))
                .andExpect(status().isOk());
        mvc.perform(json(patch("/api/cancellations/B1/follow-ups/F1"), "{\"note\":\"later\"}").with(as(UserType.AGENCY_OWNER)))
                .andExpect(status().isOk());
        mvc.perform(patch("/api/cancellations/B1/follow-ups/F1/done").with(as(UserType.AGENT))).andExpect(status().isOk());

        verify(cancellationService).addFollowUp(eq("B1"), any());
        verify(cancellationService).updateFollowUp(eq("B1"), eq("F1"), any());
        verify(cancellationService).completeFollowUp("B1", "F1");
    }

    @Test
    void anAccountantOnlyReadsAndBadInputIs400() throws Exception {
        mvc.perform(json(post("/api/cancellations/B1/follow-ups"), "{\"kind\":\"FOLLOW_UP\",\"inDays\":1}").with(as(UserType.ACCOUNTANT)))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/cancellations/B1/follow-ups/F1/done").with(as(UserType.ACCOUNTANT))).andExpect(status().isForbidden());
        mvc.perform(get("/api/cancellations").with(as(UserType.SUPER_ADMIN))).andExpect(status().isForbidden());
        mvc.perform(json(post("/api/cancellations/B1/follow-ups"), "{\"inDays\":40,\"note\":\"" + "x".repeat(501) + "\"}").with(as(UserType.AGENT)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.errors.kind").value("Pick what to schedule"))
                .andExpect(jsonPath("$.details.errors.inDays").value("Pick 1 to 30 days"))
                .andExpect(jsonPath("$.details.errors.note").value("Write at most 500 characters"));
        mvc.perform(json(patch("/api/cancellations/B1/follow-ups/F1"), "{\"note\":\"" + "x".repeat(501) + "\"}").with(as(UserType.AGENT)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/cancellations").param("size", "500").with(as(UserType.AGENT))).andExpect(status().isBadRequest());
        verifyNoInteractions(cancellationService);
    }
}
