package com.voyra.crm.exception;

import com.voyra.crm.enums.Sku;
import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * A write the interceptors let through on purpose but the service must still refuse - e.g. reactivating
 * a staff login, which shares its route with the always-allowed deactivation. Same status and code as
 * the interceptors: 423 TENANT_SUSPENDED, 402 READ_ONLY, or 402 SKU_REQUIRED naming the SKU (a staff login the plan lacks).
 */
@Getter
public class TenantWriteRefusedException extends RuntimeException {

    public static final String SUSPENDED_MESSAGE = "This agency is suspended. Contact Voyra.";
    public static final String READ_ONLY_MESSAGE = "Your subscription is read-only. Renew in Subscription to make changes.";

    public static final String SKU_REQUIRED_MESSAGE = "Your plan doesn't include this feature. Upgrade in Subscription.";

    private final HttpStatus status;
    private final String code;
    /** Only for SKU_REQUIRED. */
    private final Sku sku;

    private TenantWriteRefusedException(HttpStatus status, String code, String message, Sku sku) {
        super(message);
        this.status = status;
        this.code = code;
        this.sku = sku;
    }

    public static TenantWriteRefusedException suspended() {
        return new TenantWriteRefusedException(HttpStatus.LOCKED, "TENANT_SUSPENDED", SUSPENDED_MESSAGE, null);
    }

    public static TenantWriteRefusedException readOnly() {
        return new TenantWriteRefusedException(HttpStatus.PAYMENT_REQUIRED, "READ_ONLY", READ_ONLY_MESSAGE, null);
    }

    /** Same body as EntitlementInterceptor's refusal: code SKU_REQUIRED plus the SKU's name. */
    public static TenantWriteRefusedException skuRequired(Sku sku) {
        return new TenantWriteRefusedException(HttpStatus.PAYMENT_REQUIRED, "SKU_REQUIRED", SKU_REQUIRED_MESSAGE, sku);
    }
}
