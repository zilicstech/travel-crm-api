package com.voyra.crm.controller;

import com.voyra.crm.config.SecurityConfig;
import com.voyra.crm.enums.BoardingPassStatus;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.security.JwtAuthenticationFilter;
import com.voyra.crm.security.JwtService;
import com.voyra.crm.security.RestAuthenticationEntryPoint;
import com.voyra.crm.service.BoardingPassService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;

import static com.voyra.crm.controller.WebTestSupport.FILE;
import static com.voyra.crm.controller.WebTestSupport.as;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(BoardingPassController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class, RestAuthenticationEntryPoint.class})
class BoardingPassControllerTest {

    @Autowired private MockMvc mvc;
    @MockBean private BoardingPassService service;

    @Test
    void ownerAndAgentListMarkResetAndDownload() throws Exception {
        mvc.perform(get("/api/boarding-passes").with(as(UserType.AGENT))).andExpect(status().isOk());
        mvc.perform(get("/api/boarding-passes").param("date", "2026-11-23").param("pending", "false").param("page", "1").param("size", "5")
                .with(as(UserType.AGENCY_OWNER))).andExpect(status().isOk());
        mvc.perform(get("/api/boarding-passes/booking/B1").with(as(UserType.AGENT))).andExpect(status().isOk());
        mvc.perform(multipart("/api/boarding-passes").file(FILE).param("bookingId", "B1").param("travelDate", "2026-11-23")
                .param("status", "GENERATED").with(as(UserType.AGENT))).andExpect(status().isOk());
        mvc.perform(multipart("/api/boarding-passes").param("bookingId", "B1").param("travelDate", "2026-11-23")
                .param("status", "NOT_NEEDED").param("reason", "Checks in himself").with(as(UserType.AGENCY_OWNER))).andExpect(status().isOk());
        mvc.perform(delete("/api/boarding-passes/P1").with(as(UserType.AGENT))).andExpect(status().isNoContent());

        verify(service).list(null, true, PageRequest.of(0, 20));
        verify(service).list(LocalDate.of(2026, 11, 23), false, PageRequest.of(1, 5));
        verify(service).forBooking("B1");
        verify(service).mark(eq("B1"), eq(LocalDate.of(2026, 11, 23)), eq(BoardingPassStatus.GENERATED), isNull(), any());
        verify(service).mark(eq("B1"), eq(LocalDate.of(2026, 11, 23)), eq(BoardingPassStatus.NOT_NEEDED), eq("Checks in himself"), isNull());
        verify(service).reset("P1");
    }

    @Test
    void theProofDownloadsAsAnAttachmentWithItsTypeOrAsBytes() throws Exception {
        given(service.file("P1")).willReturn(new BoardingPassService.ProofFile(new ByteArrayResource(new byte[]{1}), "bp.pdf", "application/pdf"));
        given(service.file("P2")).willReturn(new BoardingPassService.ProofFile(new ByteArrayResource(new byte[]{1}), "bp.bin", null));
        mvc.perform(get("/api/boarding-passes/P1/file").with(as(UserType.AGENT)))
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"bp.pdf\""))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        mvc.perform(get("/api/boarding-passes/P2/file").with(as(UserType.AGENT))).andExpect(content().contentType(MediaType.APPLICATION_OCTET_STREAM));
    }

    @Test
    void theAccountantAndBadInputAreRefused() throws Exception {
        mvc.perform(get("/api/boarding-passes").with(as(UserType.ACCOUNTANT))).andExpect(status().isForbidden());
        mvc.perform(get("/api/boarding-passes").param("date", "tomorrow").with(as(UserType.AGENT))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/boarding-passes").param("size", "500").with(as(UserType.AGENT))).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/boarding-passes").param("bookingId", "B1").param("travelDate", "2026-11-23").param("status", "MAYBE")
                .with(as(UserType.AGENT))).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
