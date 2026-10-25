package com.voyra.crm.enums;

/** One credential row per provider covers either environment, never both at once. */
public enum SupplierEnvironment {
    UAT, PROD,
    /** A booking made against the mock provider; simulated money only, like UAT. */
    MOCK
}
