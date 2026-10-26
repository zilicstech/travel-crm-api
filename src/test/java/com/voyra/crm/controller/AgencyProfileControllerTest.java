package com.voyra.crm.controller;

import com.voyra.crm.config.SecurityConfig;
import com.voyra.crm.dto.AgencyProfileResponse;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.exception.NotFoundException;
import com.voyra.crm.security.CustomUserPrincipal;
import com.voyra.crm.security.JwtAuthenticationFilter;
import com.voyra.crm.security.JwtService;
import com.voyra.crm.security.RestAuthenticationEntryPoint;
import com.voyra.crm.service.AgencyProfileService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Logo endpoints: the owner uploads and removes; every agency role reads the image. */
@WebMvcTest(AgencyProfileController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class, RestAuthenticationEntryPoint.class})
class AgencyProfileControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private AgencyProfileService service;

    private static RequestPostProcessor as(UserType type) {
        return authentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal("U1", "u", type, "T1"), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + type.name()))));
    }

    private static MockMultipartFile logo() {
        return new MockMultipartFile("file", "logo.png", "image/png", new byte[]{1, 2, 3});
    }

    @Test
    void theOwnerUploadsAndRemovesTheLogo() throws Exception {
        given(service.uploadLogo(any())).willReturn(AgencyProfileResponse.builder().logoKey("k").hasLogo(true).build());
        mockMvc.perform(multipart(HttpMethod.PUT, "/api/agency/profile/logo").file(logo()).with(as(UserType.AGENCY_OWNER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hasLogo").value(true));

        given(service.removeLogo()).willReturn(AgencyProfileResponse.builder().hasLogo(false).build());
        mockMvc.perform(delete("/api/agency/profile/logo").with(as(UserType.AGENCY_OWNER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hasLogo").value(false));
    }

    @Test
    void staffCannotChangeTheLogo() throws Exception {
        for (UserType type : List.of(UserType.AGENT, UserType.ACCOUNTANT)) {
            mockMvc.perform(multipart(HttpMethod.PUT, "/api/agency/profile/logo").file(logo()).with(as(type)))
                    .andExpect(status().isForbidden());
            mockMvc.perform(delete("/api/agency/profile/logo").with(as(type))).andExpect(status().isForbidden());
            mockMvc.perform(get("/api/agency/profile").with(as(type))).andExpect(status().isForbidden());
        }
        verifyNoInteractions(service);
    }

    @Test
    void everyAgencyRoleReadsTheLogoWithAShortPrivateCache() throws Exception {
        given(service.logo()).willReturn(new AgencyProfileService.Logo(new ByteArrayResource(new byte[]{9, 8, 7}), "image/webp"));
        for (UserType type : List.of(UserType.AGENCY_OWNER, UserType.AGENT, UserType.ACCOUNTANT)) {
            mockMvc.perform(get("/api/agency/profile/logo").with(as(type)))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType("image/webp"))
                    .andExpect(header().string("Cache-Control", "private, max-age=300"))
                    .andExpect(content().bytes(new byte[]{9, 8, 7}));
        }
        mockMvc.perform(get("/api/agency/profile/logo").with(as(UserType.SUPER_ADMIN))).andExpect(status().isForbidden());
    }

    @Test
    void noLogoIs404() throws Exception {
        given(service.logo()).willThrow(new NotFoundException("This agency has no logo"));
        mockMvc.perform(get("/api/agency/profile/logo").with(as(UserType.AGENT))).andExpect(status().isNotFound());
    }
}
