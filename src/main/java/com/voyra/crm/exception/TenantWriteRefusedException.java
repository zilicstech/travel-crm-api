package com.voyra.crm.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * A write the interceptors let through on purpose but the service must still refuse - e.g. reactivating
 * a staff login, which shares its route with the always-allowed deactivation. Same status and code as
 * the interceptors: 423 TENANT_SUSPENDED or 402 READ_ONLY.
 */
@Getter
public class TenantWriteRefusedException extends RuntimeException {

    public static final String SUSPENDED_MESSAGE = "This agency is suspended. Contact Voyra.";
    public static final String READ_ONLY_MESSAGE = "Your subscription is read-only. Renew in Subscription to make changes.";

    private final HttpStatus status;
    private final String code;

    private TenantWriteRefusedException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static TenantWriteRefusedException suspended() {
        return new TenantWriteRefusedException(HttpStatus.LOCKED, "TENANT_SUSPENDED", SUSPENDED_MESSAGE);
    }

    public static TenantWriteRefusedException readOnly() {
        return new TenantWriteRefusedException(HttpStatus.PAYMENT_REQUIRED, "READ_ONLY", READ_ONLY_MESSAGE);
    }
}
