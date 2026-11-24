package com.voyra.crm.controller;

import com.voyra.crm.config.SecurityConfig;
import com.voyra.crm.dto.VisaValuesUpdateRequest;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.security.JwtAuthenticationFilter;
import com.voyra.crm.security.JwtService;
import com.voyra.crm.security.RestAuthenticationEntryPoint;
import com.voyra.crm.service.VisaService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static com.voyra.crm.controller.WebTestSupport.FILE;
import static com.voyra.crm.controller.WebTestSupport.as;
import static com.voyra.crm.controller.WebTestSupport.json;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(VisaController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class, RestAuthenticationEntryPoint.class})
class VisaControllerWebTest {

    private static final String CASE = "{\"clientId\":\"C1\",\"memberId\":\"M1\",\"country\":\"Kenya\",\"visaType\":\"Tourist\",\"values\":{\"f1\":\"RECVD\"}}";

    @Autowired private MockMvc mvc;
    @MockBean private VisaService service;

    @Test
    void opensACaseWithItsFilesNamedByField() throws Exception {
        MockMultipartFile request = new MockMultipartFile("request", "", MediaType.APPLICATION_JSON_VALUE, CASE.getBytes());
        MockMultipartFile contract = new MockMultipartFile("f9", "contract.pdf", "application/pdf", new byte[]{1});
        mvc.perform(multipart("/api/visas").file(request).file(contract).with(as(UserType.AGENT))).andExpect(status().isOk());
        verify(service).createVisa(argThat(r -> "M1".equals(r.getMemberId()) && "RECVD".equals(r.getValues().get("f1"))),
                argThat((Map<String, org.springframework.web.multipart.MultipartFile> files) -> files.keySet().equals(java.util.Set.of("f9"))));
    }

    @Test
    void theMultipartRequestIsValidatedAndATravellerIsRequired() throws Exception {
        MockMultipartFile request = new MockMultipartFile("request", "", MediaType.APPLICATION_JSON_VALUE,
                "{\"clientId\":\"C1\",\"country\":\"Kenya\",\"visaType\":\"Tourist\"}".getBytes());
        mvc.perform(multipart("/api/visas").file(request).with(as(UserType.AGENT)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.errors.memberId").value("Pick the traveller"));
        mvc.perform(json(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/visas"),
                "{\"clientId\":\"C1\",\"memberId\":\"M1\",\"country\":\"Kenya\",\"visaType\":\"Tourist\",\"purchaseCurrency\":\"usd\"}")
                .with(as(UserType.AGENT))).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void editsValuesAndFiles() throws Exception {
        mvc.perform(json(patch("/api/visas/V1/values"), "{\"version\":3,\"values\":{\"f1\":\"\"},\"remarks\":\"x\"}").with(as(UserType.AGENT)))
                .andExpect(status().isOk());
        verify(service).updateValues(eq("V1"), eq(VisaValuesUpdateRequest.builder().version(3L).values(Map.of("f1", "")).remarks("x").build()));
        mvc.perform(json(patch("/api/visas/V1/values"), "{}").with(as(UserType.AGENT)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.details.errors.version").value("Send the version you read"));

        mvc.perform(multipart("/api/visas/V1/files/f9").file(FILE).with(r -> { r.setMethod("PUT"); return r; }).with(as(UserType.AGENCY_OWNER)))
                .andExpect(status().isOk());
        verify(service).uploadFile(eq("V1"), eq("f9"), any());
        mvc.perform(delete("/api/visas/V1/files/f9").with(as(UserType.AGENT))).andExpect(status().isNoContent());
        verify(service).deleteFile("V1", "f9");
    }

    @Test
    void aFileDownloadsAsAnAttachmentWithASafeName() throws Exception {
        given(service.file("V1", "f9")).willReturn(new VisaService.StoredVisaFile(new ByteArrayResource(new byte[]{1}), "con\"tract é.pdf", "application/pdf"));
        given(service.file("V1", "f8")).willReturn(new VisaService.StoredVisaFile(new ByteArrayResource(new byte[]{1}), "x.bin", null));
        mvc.perform(get("/api/visas/V1/files/f9").with(as(UserType.AGENT)))
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, org.hamcrest.Matchers.startsWith("attachment; filename=")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        mvc.perform(get("/api/visas/V1/files/f8").with(as(UserType.AGENT))).andExpect(content().contentType(MediaType.APPLICATION_OCTET_STREAM));
    }

    @Test
    void theAccountantHasNoVisaAccess() throws Exception {
        mvc.perform(get("/api/visas/V1/files/f9").with(as(UserType.ACCOUNTANT))).andExpect(status().isForbidden());
        mvc.perform(json(patch("/api/visas/V1/values"), "{\"version\":1}").with(as(UserType.ACCOUNTANT))).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
}
