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
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * A SUSPENDED agency signs in and reads, but every tenant-scoped write is refused with 423. Reads include the
 * few POSTs that only compute or look up (searches, previews, duplicate checks) - listed exactly in
 * {@link ReadRequests#READ_STYLE_POSTS}; a POST that records anything (e.g. a refund check) stays blocked.
 * An owner may still deactivate or delete a staff login ({@link ReadRequests#isStaffCutOff}), and an owner or agent may
 * still download a customer e-ticket (it records the download and posts no money).
 * Runs after JwtAuthenticationFilter, so TenantContext already holds the request's tenant.
 */
@Component
@RequiredArgsConstructor
public class TenantStatusInterceptor implements HandlerInterceptor {

    private static final List<String> OPEN_PREFIXES = List.of("/api/auth/", "/api/public/", "/api/platform/");
    private static final String TICKET_DOWNLOAD = "/api/platform-bookings/{id}/tickets";
    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                             @NonNull Object handler) throws IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        String tenantId = TenantContext.getTenantId();
        if (ReadRequests.isRead(request, path)
                || ReadRequests.isStaffCutOff(request, path)
                || ("POST".equals(request.getMethod()) && MATCHER.match(TICKET_DOWNLOAD, path))
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
