package com.voyra.crm.enums;

/** A supplier row's environment, fixed when the row is created; a new environment is a new row. */
public enum SupplierEnvironment {
    UAT, PROD,
    /** A booking made against the mock provider; simulated money only, like UAT. */
    MOCK
}
