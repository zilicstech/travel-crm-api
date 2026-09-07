package com.voyra.crm.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyra.crm.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the blueprint §6.1 exception -> status contract using a minimal fixture
 * controller, isolated from security concerns (this test is about exception mapping only).
 */
@WebMvcTest(controllers = GlobalExceptionHandlerFixtureController.class)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@Import(JwtService.class)
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void illegalArgumentMapsTo400() throws Exception {
        mockMvc.perform(get("/test/illegal-argument"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("bad input"));
    }

    @Test
    void illegalStateMapsTo409() throws Exception {
        mockMvc.perform(get("/test/illegal-state"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("bad state"));
    }

    @Test
    void accessDeniedMapsTo403() throws Exception {
        mockMvc.perform(get("/test/access-denied"))
                .andExpect(status().isForbidden());
    }

    /** The client message must never echo the real exception - it would leak the request URI. */
    @Test
    void supplierExceptionMapsTo502WithAFixedMessage() throws Exception {
        mockMvc.perform(get("/test/supplier-failure"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message").value("The supplier could not be reached. Please try again."))
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("tripjack"))));
    }

    @Test
    void validationFailureMapsTo400WithFieldErrors() throws Exception {
        GlobalExceptionHandlerFixtureController.FixtureRequest invalid =
                new GlobalExceptionHandlerFixtureController.FixtureRequest();
        invalid.setName("");

        mockMvc.perform(post("/test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.errors.name").exists());
    }

    @Test
    void everyErrorBodyCarriesTheStandardEnvelope() throws Exception {
        mockMvc.perform(get("/test/illegal-argument"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.path").value("/test/illegal-argument"))
                .andExpect(jsonPath("$.timestamp").exists());
    }
}
