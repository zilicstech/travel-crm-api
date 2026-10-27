package com.voyra.crm.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

/** The only place services should read the current principal - never touch SecurityContextHolder directly. */
public final class SecurityContextUtil {

    private SecurityContextUtil() {
    }

    /** Inside a {@link SystemActor} scope the system principal wins over whoever is signed in. */
    public static Optional<CustomUserPrincipal> getCurrentUser() {
        Optional<CustomUserPrincipal> system = SystemActor.principal();
        if (system.isPresent()) {
            return system;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof CustomUserPrincipal p)) {
            return Optional.empty();
        }
        return Optional.of(p);
    }

    public static CustomUserPrincipal getCurrentUserOrThrow() {
        return getCurrentUser()
                .orElseThrow(() -> new IllegalStateException("No authenticated user in security context"));
    }

    public static Optional<String> getCurrentUserId() {
        return getCurrentUser().map(CustomUserPrincipal::userId);
    }

    /** Single-line audit string for logs. */
    public static String getAuditInfo() {
        return getCurrentUser()
                .map(p -> "userId=%s, username=%s, tenantId=%s, role=%s"
                        .formatted(p.userId(), p.username(), p.tenantId(), p.userType()))
                .orElse("unauthenticated");
    }
}
