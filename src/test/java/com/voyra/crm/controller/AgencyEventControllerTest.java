package com.voyra.crm.controller;

import com.voyra.crm.config.SecurityConfig;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.security.JwtAuthenticationFilter;
import com.voyra.crm.security.JwtService;
import com.voyra.crm.security.RestAuthenticationEntryPoint;
import com.voyra.crm.service.AgencyEventHub;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static com.voyra.crm.controller.WebTestSupport.as;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AgencyEventController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class, RestAuthenticationEntryPoint.class})
class AgencyEventControllerTest {

    @Autowired private MockMvc mvc;
    @MockBean private AgencyEventHub hub;

    @Test
    void everyAgencyRoleOpensAStreamThatProxiesMayNotBuffer() throws Exception {
        when(hub.subscribe(any())).thenAnswer(i -> new SseEmitter());
        for (UserType role : new UserType[] {UserType.AGENCY_OWNER, UserType.AGENT, UserType.ACCOUNTANT}) {
            mvc.perform(get("/api/events").with(as(role)))
                    .andExpect(status().isOk())
                    .andExpect(request().asyncStarted())
                    .andExpect(header().string("X-Accel-Buffering", "no"))
                    .andExpect(header().string("Cache-Control", "no-cache"));
        }
    }

    @Test
    void thePlatformAdminAndAnonymousCallersGetNone() throws Exception {
        mvc.perform(get("/api/events").with(as(UserType.SUPER_ADMIN))).andExpect(status().isForbidden());
        mvc.perform(get("/api/events")).andExpect(status().isUnauthorized());
        verifyNoInteractions(hub);
    }
}
