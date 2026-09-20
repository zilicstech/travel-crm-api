package com.voyra.crm.enums;

/**
 * Whether the GST on a supplier bill is available as input tax credit. Recorded per bill, never
 * derived - e.g. a hotel room at the 5%-without-ITC slab vs the 18%-with-ITC slab looks identical
 * on our side except for what the supplier printed.
 */
public enum ItcEligibility {
    ELIGIBLE, INELIGIBLE, BLOCKED
}
