package com.voyra.crm.enums;

/**
 * What is being billed, for tax-rate lookup purposes. {@code OVERSEAS_PACKAGE} is the only
 * value that ever triggers TCS under s.206C(1G).
 */
public enum SupplyNature {
    DOMESTIC_PACKAGE, OVERSEAS_PACKAGE, AIR_TICKET, HOTEL_ONLY, VISA_SERVICE, INSURANCE, OTHER
}
