package com.voyra.crm.enums;

/** Lifecycle of a public agency application: DRAFT until the applicant submits, then the platform desk decides. */
public enum ApplicationStatus {
    DRAFT, SUBMITTED, UNDER_REVIEW, MORE_INFO, APPROVED, REJECTED
}
