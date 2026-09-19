package com.voyra.crm.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyra.crm.cache.AgentCache;
import com.voyra.crm.cache.PlatformAdminCache;
import com.voyra.crm.cache.TenantCache;
import com.voyra.crm.context.TenantContext;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

/** Single place that resolves auth + tenant context for every incoming request. */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String X_TENANT_ID_HEADER = "X-Tenant-Id";

    private final JwtService jwtService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                     @NonNull HttpServletResponse response,
                                     @NonNull FilterChain filterChain)
            throws ServletException, IOException {
        String token = extractToken(request);
        try {
            if (StringUtils.hasText(token)) {
                Claims claims = jwtService.validateAndGetClaims(token);
                if (claims != null) {
                    CustomUserPrincipal principal = jwtService.getPrincipalFromClaims(claims);

                    String tenantId = principal.tenantId();
                    if (principal.isSuperAdmin()) {
                        String override = request.getHeader(X_TENANT_ID_HEADER);
                        if (StringUtils.hasText(override)) {
                            tenantId = override.trim();
                        }
                    }
                    if (tenantId != null) {
                        if (!TenantCache.isValidTenant(tenantId)) {
                            writeUnauthorized(response, "Invalid or inactive tenant");
                            return;
                        }
                        TenantContext.setTenantId(tenantId);
                    }

                    // JWTs are stateless: without a per-request liveness check, a token issued
                    // before deactivation keeps working until it expires.
                    if (!isPrincipalActive(principal)) {
                        writeUnauthorized(response, "Account is inactive");
                        return;
                    }

                    SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken(
                                    principal, null, jwtService.getAuthoritiesFromClaims(claims)));
                }
            }
            filterChain.doFilter(request, response);
        } finally {
            // MANDATORY: request threads are pooled and reused.
            TenantContext.clear();
        }
    }

    private boolean isPrincipalActive(CustomUserPrincipal principal) {
        return switch (principal.userType()) {
            case SUPER_ADMIN -> PlatformAdminCache.isActive(principal.userId());
            // Owner IS the Tenant record - tenant liveness (already checked above) covers it.
            case AGENCY_OWNER -> true;
            case AGENT, ACCOUNTANT -> AgentCache.isActive(principal.userId());
        };
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader(AUTHORIZATION_HEADER);
        if (StringUtils.hasText(header) && header.startsWith(BEARER_PREFIX)) {
            return header.substring(BEARER_PREFIX.length());
        }
        return null;
    }

    private void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(Map.of(
                "success", false,
                "message", message,
                "status", HttpServletResponse.SC_UNAUTHORIZED)));
    }
}
