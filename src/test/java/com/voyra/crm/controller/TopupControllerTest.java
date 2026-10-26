package com.voyra.crm.controller;

import com.voyra.crm.config.SecurityConfig;
import com.voyra.crm.dto.GatewayTopupResponse;
import com.voyra.crm.dto.TopupResponse;
import com.voyra.crm.enums.TopupStatus;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.security.CustomUserPrincipal;
import com.voyra.crm.security.JwtAuthenticationFilter;
import com.voyra.crm.security.JwtService;
import com.voyra.crm.security.RestAuthenticationEntryPoint;
import com.voyra.crm.service.TopupService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TopupController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class, RestAuthenticationEntryPoint.class})
class TopupControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @MockBean
    private TopupService service;

    private static RequestPostProcessor as(UserType type) {
        return authentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal("U1", "u", type, "T1"), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + type.name()))));
    }

    private static MockMultipartFile proof() {
        return new MockMultipartFile("proof", "slip.pdf", "application/pdf", "%PDF-1.4".getBytes());
    }

    @Test
    void ownerRequestsATopup() throws Exception {
        given(service.createBank(any(), any(), any(), any())).willReturn(TopupResponse.builder().id("R1").status(TopupStatus.PENDING).build());
        mockMvc.perform(multipart("/api/wallet/topups/bank").file(proof()).param("amount", "50000").param("bankRef", "SBIN1234")
                        .with(as(UserType.AGENCY_OWNER)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value("R1"));
    }

    @Test
    void shortBankRefIsAFieldError() throws Exception {
        mockMvc.perform(multipart("/api/wallet/topups/bank").file(proof()).param("amount", "50000").param("bankRef", "ab")
                        .with(as(UserType.AGENCY_OWNER)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.details.errors.bankRef").exists());
        verifyNoInteractions(service);
    }

    /** R12: a JSON body on the multipart endpoint is 415 for every role (was 500), and never reaches the service. */
    @Test
    void aJsonBodyOnTheMultipartEndpointIs415() throws Exception {
        for (UserType t : new UserType[]{UserType.AGENCY_OWNER, UserType.ACCOUNTANT, UserType.AGENT}) {
            mockMvc.perform(post("/api/wallet/topups/bank").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"amount\":100,\"bankRef\":\"SBIN1234\"}").with(as(t)))
                    .andExpect(status().isUnsupportedMediaType());
        }
        verifyNoInteractions(service);
    }

    /** R12: a multipart body that cannot be parsed is the caller's mistake (400), not a server error. */
    @Test
    void anUnparseableMultipartBodyIs400() throws Exception {
        mockMvc.perform(post("/api/wallet/topups/bank").contentType("multipart/form-data") // no boundary
                        .content("garbage").with(as(UserType.AGENCY_OWNER)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void accountantAndAgentCannotRequest() throws Exception {
        for (UserType t : new UserType[]{UserType.ACCOUNTANT, UserType.AGENT}) {
            mockMvc.perform(multipart("/api/wallet/topups/bank").file(proof()).param("amount", "50000").param("bankRef", "SBIN1234")
                    .with(as(t))).andExpect(status().isForbidden());
        }
        verifyNoInteractions(service);
    }

    @Test
    void accountantListsButAgentCannot() throws Exception {
        given(service.listForAgency("T1")).willReturn(List.of());
        mockMvc.perform(get("/api/wallet/topups").with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());
        mockMvc.perform(get("/api/wallet/topups").with(as(UserType.AGENT))).andExpect(status().isForbidden());
    }

    @Test
    void ownerStartsAGatewayTopupAccountantCannot() throws Exception {
        given(service.createGateway(eq("T1"), eq("U1"), any(), any())).willReturn(GatewayTopupResponse.builder()
                .topupId("R1").gatewayOrderId("order_1").amountPaise(5000000L).provider("stub").build());
        mockMvc.perform(post("/api/wallet/topups/gateway").contentType(MediaType.APPLICATION_JSON).content("{\"amount\":50000}")
                        .with(as(UserType.AGENCY_OWNER)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.gatewayOrderId").value("order_1"))
                .andExpect(jsonPath("$.amountPaise").value(5000000));
        mockMvc.perform(post("/api/wallet/topups/gateway").contentType(MediaType.APPLICATION_JSON).content("{\"amount\":50000}")
                .with(as(UserType.ACCOUNTANT))).andExpect(status().isForbidden());
    }

    @Test
    void gatewayAmountIsValidated() throws Exception {
        mockMvc.perform(post("/api/wallet/topups/gateway").contentType(MediaType.APPLICATION_JSON).content("{\"amount\":0.5}")
                        .with(as(UserType.AGENCY_OWNER)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.details.errors.amount").exists());
        verifyNoInteractions(service);
    }

    @Test
    void ownerSimulatesOnlyTheirOwnTenant() throws Exception {
        given(service.simulate("T1", "R1")).willReturn(TopupResponse.builder().id("R1").status(TopupStatus.APPROVED).build());
        mockMvc.perform(post("/api/wallet/topups/R1/simulate").with(as(UserType.AGENCY_OWNER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        mockMvc.perform(post("/api/wallet/topups/R1/simulate").with(as(UserType.ACCOUNTANT))).andExpect(status().isForbidden());
    }
}
