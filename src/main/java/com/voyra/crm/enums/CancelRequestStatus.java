package com.voyra.crm.enums;

import java.util.Set;

/** A cancellation request's state; OPEN, IN_PROGRESS and COMPLETING are active (one per booking). */
public enum CancelRequestStatus {
    OPEN, IN_PROGRESS, COMPLETING, COMPLETED, REJECTED, WITHDRAWN;

    public static final Set<CancelRequestStatus> ACTIVE = Set.of(OPEN, IN_PROGRESS, COMPLETING);
}
