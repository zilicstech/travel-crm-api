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
    @MockBean private com.voyra.crm.service.VisaExportService exportService;

    @Test
    void theSheetSearchesWithFieldFiltersAndThePlainListStays() throws Exception {
        mvc.perform(get("/api/visas").with(as(UserType.AGENT))).andExpect(status().isOk());
        verify(service).listVisas();
        mvc.perform(get("/api/visas").param("page", "1").param("size", "50").param("sort", "field.f1,desc").param("q", "ravi")
                .param("country", "Kenya").param("status", "SUBMITTED").param("agentId", "A2").param("openedFrom", "2026-11-01")
                .param("openedTo", "2026-11-30").param("f.f1", "RECVD").param("f.f2.from", "2026-01-01").with(as(UserType.AGENCY_OWNER)))
                .andExpect(status().isOk());
        verify(service).searchVisas(eq(new com.voyra.crm.models.VisaListFilter("ravi", "Kenya", com.voyra.crm.enums.VisaStatus.SUBMITTED, "A2",
                java.time.LocalDate.of(2026, 11, 1), java.time.LocalDate.of(2026, 11, 30), Map.of("f1", "RECVD", "f2.from", "2026-01-01"))),
                eq("field.f1,desc"), eq(org.springframework.data.domain.PageRequest.of(1, 50)));
        mvc.perform(get("/api/visas").param("page", "0").param("status", "MAYBE").with(as(UserType.AGENT))).andExpect(status().isBadRequest());
    }

    @Test
    void theExportDownloadsAsCsvAndSaysWhenItWasCut() throws Exception {
        given(exportService.export(any(), any())).willReturn(new com.voyra.crm.service.VisaExportService.Export("No.\n1\n", 1, true));
        mvc.perform(get("/api/visas/export.csv").param("country", "Kenya").param("f.f1", "RECVD").param("sort", "caseNo").with(as(UserType.AGENT)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"visa-applications.csv\""))
                .andExpect(header().string("X-Truncated", "true"))
                .andExpect(content().string("No.\n1\n"));
        verify(exportService).export(eq(new com.voyra.crm.models.VisaListFilter(null, "Kenya", null, null, null, null, Map.of("f1", "RECVD"))), eq("caseNo"));
    }

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
