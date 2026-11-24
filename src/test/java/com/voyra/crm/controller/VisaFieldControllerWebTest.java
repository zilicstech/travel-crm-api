package com.voyra.crm.controller;

import com.voyra.crm.config.SecurityConfig;
import com.voyra.crm.dto.VisaFieldOrderRequest;
import com.voyra.crm.dto.VisaFieldRequest;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.enums.VisaFieldScope;
import com.voyra.crm.enums.VisaFieldType;
import com.voyra.crm.security.JwtAuthenticationFilter;
import com.voyra.crm.security.JwtService;
import com.voyra.crm.security.RestAuthenticationEntryPoint;
import com.voyra.crm.service.VisaFieldService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static com.voyra.crm.controller.WebTestSupport.as;
import static com.voyra.crm.controller.WebTestSupport.json;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(VisaFieldController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class, RestAuthenticationEntryPoint.class})
class VisaFieldControllerWebTest {

    private static final String FIELD = "{\"scope\":\"COUNTRY\",\"country\":\"Kenya\",\"label\":\"MEDICAL\",\"type\":\"DROPDOWN\",\"options\":[\"RECVD\"],\"mandatory\":true}";

    @Autowired private MockMvc mvc;
    @MockBean private VisaFieldService service;

    @Test
    void ownerAndAgentReadTheFields() throws Exception {
        mvc.perform(get("/api/visa-fields").param("country", "Kenya").with(as(UserType.AGENT))).andExpect(status().isOk());
        mvc.perform(get("/api/visa-fields").with(as(UserType.AGENCY_OWNER))).andExpect(status().isOk());
        mvc.perform(get("/api/visa-fields/all").with(as(UserType.AGENT))).andExpect(status().isOk());
        mvc.perform(get("/api/visa-fields/countries").with(as(UserType.AGENT))).andExpect(status().isOk());
        verify(service).forCountry("Kenya");
        verify(service).forCountry(null);
        verify(service).all();
        verify(service).countries();
    }

    @Test
    void onlyTheOwnerEdits() throws Exception {
        mvc.perform(json(post("/api/visa-fields"), FIELD).with(as(UserType.AGENT))).andExpect(status().isForbidden());
        mvc.perform(json(put("/api/visa-fields/F1"), FIELD).with(as(UserType.AGENT))).andExpect(status().isForbidden());
        mvc.perform(delete("/api/visa-fields/F1").with(as(UserType.AGENT))).andExpect(status().isForbidden());
        mvc.perform(json(put("/api/visa-fields/order"), "{\"scope\":\"COMMON\",\"ids\":[\"a\"]}").with(as(UserType.AGENT))).andExpect(status().isForbidden());
        mvc.perform(get("/api/visa-fields").with(as(UserType.ACCOUNTANT))).andExpect(status().isForbidden());
        verifyNoInteractions(service);

        mvc.perform(json(post("/api/visa-fields"), FIELD).with(as(UserType.AGENCY_OWNER))).andExpect(status().isOk());
        mvc.perform(json(put("/api/visa-fields/F1"), FIELD).with(as(UserType.AGENCY_OWNER))).andExpect(status().isOk());
        mvc.perform(json(put("/api/visa-fields/order"), "{\"scope\":\"COMMON\",\"ids\":[\"b\",\"a\"]}").with(as(UserType.AGENCY_OWNER))).andExpect(status().isOk());
        mvc.perform(delete("/api/visa-fields/F1").with(as(UserType.AGENCY_OWNER))).andExpect(status().isNoContent());
        VisaFieldRequest expected = VisaFieldRequest.builder().scope(VisaFieldScope.COUNTRY).country("Kenya").label("MEDICAL")
                .type(VisaFieldType.DROPDOWN).options(List.of("RECVD")).mandatory(true).build();
        verify(service).create(expected);
        verify(service).update(eq("F1"), eq(expected));
        verify(service).reorder(new VisaFieldOrderRequest(VisaFieldScope.COMMON, null, List.of("b", "a")));
        verify(service).delete("F1");
    }

    @Test
    void validationErrorsComeBackPerField() throws Exception {
        mvc.perform(json(post("/api/visa-fields"), "{\"scope\":\"COMMON\",\"label\":\"" + "x".repeat(61) + "\"}").with(as(UserType.AGENCY_OWNER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.errors.label").value("Label must be 60 characters or fewer"))
                .andExpect(jsonPath("$.details.errors.type").value("Pick a type"));
        mvc.perform(json(put("/api/visa-fields/order"), "{\"scope\":\"COMMON\",\"ids\":[]}").with(as(UserType.AGENCY_OWNER)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
