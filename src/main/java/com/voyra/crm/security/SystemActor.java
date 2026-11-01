package com.voyra.crm.security;

import com.voyra.crm.context.TenantContext;
import com.voyra.crm.enums.UserType;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Voyra's platform acting inside an agency's schema - the writer of the read-only CRM mirror of a platform booking.
 * It is a ThreadLocal scope, not a token: only {@link #call} opens it, so no request (whatever its JWT) can act as
 * the system. Inside the scope {@link SecurityContextUtil} returns an owner-level principal for the tenant with
 * user id {@value #USER_ID}, and the mirror guards ({@code PlatformManaged}) let the write through.
 */
public final class SystemActor {

    public static final String USER_ID = "system";
    public static final String NAME = "Voyra Platform";

    private static final ThreadLocal<CustomUserPrincipal> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<Author> AUTHOR = new ThreadLocal<>();

    /** Who audit rows written inside a scope name: the system itself, or the user it acts for. */
    public record Author(String id, String name) {
    }

    private SystemActor() {
    }

    /**
     * Runs {@code work} as the system in {@code tenantId}'s schema, then restores the previous tenant and actor
     * (blueprint 3.5). The tenant-scoped work must open its own {@code REQUIRES_NEW} transaction.
     */
    public static <T> T call(String tenantId, Supplier<T> work) {
        return call(tenantId, null, work);
    }

    /** As {@link #call(String, Supplier)}, with audit rows written inside naming {@code author} when it is set. */
    public static <T> T call(String tenantId, Author author, Supplier<T> work) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("The system actor needs a tenant");
        }
        String previousTenant = TenantContext.getTenantId();
        CustomUserPrincipal previousActor = CURRENT.get();
        Author previousAuthor = AUTHOR.get();
        try {
            TenantContext.setTenantId(tenantId);
            CURRENT.set(new CustomUserPrincipal(USER_ID, NAME, UserType.AGENCY_OWNER, tenantId));
            AUTHOR.set(author == null ? new Author(USER_ID, NAME) : author);
            return work.get();
        } finally {
            if (previousAuthor == null) {
                AUTHOR.remove();
            } else {
                AUTHOR.set(previousAuthor);
            }
            if (previousActor == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previousActor);
            }
            if (previousTenant == null || previousTenant.isBlank()) {
                TenantContext.clear();
            } else {
                TenantContext.setTenantId(previousTenant);
            }
        }
    }

    /** The author of the innermost open scope; only meaningful while {@link #isActive()}. */
    public static Author author() {
        return AUTHOR.get();
    }

    public static boolean isActive() {
        return CURRENT.get() != null;
    }

    static Optional<CustomUserPrincipal> principal() {
        return Optional.ofNullable(CURRENT.get());
    }
}
