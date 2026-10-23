package com.voyra.crm.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyra.crm.cache.TenantCache;
import com.voyra.crm.context.TenantContext;
import com.voyra.crm.enums.TenantStatus;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A SUSPENDED agency signs in and reads, but every tenant-scoped write is refused with 423.
 * Runs after JwtAuthenticationFilter, so TenantContext already holds the request's tenant.
 */
@Component
@RequiredArgsConstructor
public class TenantStatusInterceptor implements HandlerInterceptor {

    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    private static final List<String> OPEN_PREFIXES = List.of("/api/auth/", "/api/public/", "/api/platform/");

    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                             @NonNull Object handler) throws IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        String tenantId = TenantContext.getTenantId();
        if (READ_METHODS.contains(request.getMethod())
                || OPEN_PREFIXES.stream().anyMatch(path::startsWith)
                || tenantId == null
                || TenantCache.status(tenantId).orElse(null) != TenantStatus.SUSPENDED) {
            return true;
        }
        // setStatus, not sendError: sendError hands off to the error page and drops this body.
        response.setStatus(HttpStatus.LOCKED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(Map.of(
                "code", "TENANT_SUSPENDED",
                "message", "This agency is suspended. Contact Voyra.")));
        return false;
    }
}
