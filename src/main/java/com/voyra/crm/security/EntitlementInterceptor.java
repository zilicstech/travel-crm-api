package com.voyra.crm.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyra.crm.context.TenantContext;
import com.voyra.crm.enums.PlatformAuditAction;
import com.voyra.crm.enums.SkuRule;
import com.voyra.crm.exception.TenantWriteRefusedException;
import com.voyra.crm.models.Entitlements;
import com.voyra.crm.service.EntitlementService;
import com.voyra.crm.service.PlatformAuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.NonNull;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.Map;

/**
 * Spec §8 SKU enforcement. Runs after {@link TenantStatusInterceptor} (a SUSPENDED agency keeps its 423).
 * A controller needing a SKU the agency lacks gets 402 SKU_REQUIRED; a READ_ONLY agency keeps every read
 * (and the read-style POSTs in {@link ReadRequests}) and gets 402 READ_ONLY on writes, except that an owner
 * can always cut staff off. Spec §8's never-gated paths are left alone entirely: Starter, wallet and
 * platform bookings are free, so READ_ONLY never blocks them. Other never-gated controllers (owner
 * dashboard, staff management) need no SKU but are still READ_ONLY. Nobody is logged out, nothing is deleted. SUPER_ADMIN bypasses; acting on a tenant is audit-logged.
 */
@Component
public class EntitlementInterceptor implements HandlerInterceptor {

    private final ObjectMapper objectMapper;
    private final EntitlementService entitlementService;
    private final PlatformAuditService platformAuditService;

    // @Lazy: @WebMvcTest slices pick up every HandlerInterceptor, and most of them never reach a tenant.
    public EntitlementInterceptor(ObjectMapper objectMapper, @Lazy EntitlementService entitlementService,
                                  @Lazy PlatformAuditService platformAuditService) {
        this.objectMapper = objectMapper;
        this.entitlementService = entitlementService;
        this.platformAuditService = platformAuditService;
    }

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                             @NonNull Object handler) throws IOException {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        SkuRule rule = ControllerSkuTable.rule(method.getBeanType());
        String path = request.getRequestURI().substring(request.getContextPath().length());
        String tenantId = TenantContext.getTenantId();
        if (SecurityContextUtil.getCurrentUser().filter(CustomUserPrincipal::isSuperAdmin).isPresent()) {
            // JwtAuthenticationFilter sets a super admin's tenant only from X-Tenant-Id.
            if (tenantId != null) {
                platformAuditService.record(PlatformAuditAction.TENANT_ACT_AS, "TENANT", tenantId,
                        Map.of("method", request.getMethod(), "path", path));
            }
            return true;
        }
        if (tenantId == null || (rule == SkuRule.NEVER_GATED && ControllerSkuTable.isNeverGatedPath(path))) {
            return true;
        }
        Entitlements entitlements = entitlementService.resolve(tenantId);
        if (rule != SkuRule.NEVER_GATED && !entitlements.skus().contains(rule.sku())) {
            return refuse(response, Map.of("code", "SKU_REQUIRED", "sku", rule.sku().name(),
                    "message", "Your plan doesn't include this feature. Upgrade in Subscription."));
        }
        if (entitlements.readOnly() && !ReadRequests.isRead(request, path) && !ReadRequests.isStaffCutOff(request, path)) {
            return refuse(response, Map.of("code", "READ_ONLY", "message", TenantWriteRefusedException.READ_ONLY_MESSAGE));
        }
        return true;
    }

    private boolean refuse(HttpServletResponse response, Map<String, String> body) throws IOException {
        // setStatus, not sendError: sendError hands off to the error page and drops this body.
        response.setStatus(HttpStatus.PAYMENT_REQUIRED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(body));
        return false;
    }
}
