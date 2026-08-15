package com.voyra.crm.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyra.crm.dto.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Replaces Spring Security's default {@code Http403ForbiddenEntryPoint}, which sends a bodyless
 * 403 for missing/invalid/expired tokens. Clients need a real 401 with a body to distinguish
 * "no valid session" from "authenticated but forbidden" (the latter still goes through
 * GlobalExceptionHandler.handleAccessDenied and returns 403).
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                          AuthenticationException authException) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ApiErrorResponse body = ApiErrorResponse.of(
                "Authentication required", "Unauthorized", null,
                HttpStatus.UNAUTHORIZED.value(), request.getRequestURI());
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
