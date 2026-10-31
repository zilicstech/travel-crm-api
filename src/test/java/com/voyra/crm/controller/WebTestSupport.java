package com.voyra.crm.controller;

import com.voyra.crm.enums.UserType;
import com.voyra.crm.security.CustomUserPrincipal;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

/** Shared helpers for the controller slice tests: a signed-in principal per role and JSON / file bodies. */
final class WebTestSupport {

    static final String TENANT = "T1";
    static final MockMultipartFile FILE = new MockMultipartFile("file", "doc.pdf", "application/pdf", new byte[]{1, 2, 3});

    private WebTestSupport() {
    }

    /** A logged-in user of the given role in tenant T1 (a super admin has no tenant). */
    static RequestPostProcessor as(UserType type) {
        String tenant = type == UserType.SUPER_ADMIN ? null : TENANT;
        return authentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal("U-" + type.name(), "user@example.test", type, tenant), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + type.name()))));
    }

    static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    static ByteArrayResource bytes(String text) {
        return new ByteArrayResource(text.getBytes());
    }
}
