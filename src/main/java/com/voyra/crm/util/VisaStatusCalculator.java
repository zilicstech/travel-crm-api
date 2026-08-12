package com.voyra.crm.util;

import com.voyra.crm.entity.Visa;
import com.voyra.crm.enums.VisaStatus;

/**
 * Formalizes the visa pipeline stage as a strict priority ladder, derived from the tracked
 * booleans on every write. The mock UI computed a similar classification ad hoc from
 * overlapping boolean combinations (e.g. "appointment scheduled" and "biometric pending"
 * could both be true at once) - this replaces that with a single, unambiguous precedence:
 * REJECTED > APPROVED > SUBMITTED > APPOINTMENT_SCHEDULED > DOCUMENTS_PENDING.
 */
public final class VisaStatusCalculator {

    private VisaStatusCalculator() {
    }

    public static VisaStatus calculate(Visa visa) {
        if (Boolean.TRUE.equals(visa.getRejected())) {
            return VisaStatus.REJECTED;
        }
        if (Boolean.TRUE.equals(visa.getApproved())) {
            return VisaStatus.APPROVED;
        }
        if (Boolean.TRUE.equals(visa.getSubmittedToEmbassy())) {
            return VisaStatus.SUBMITTED;
        }
        if (visa.getAppointmentDate() != null) {
            return VisaStatus.APPOINTMENT_SCHEDULED;
        }
        return VisaStatus.DOCUMENTS_PENDING;
    }
}
