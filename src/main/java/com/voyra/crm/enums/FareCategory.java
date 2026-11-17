package com.voyra.crm.enums;

/** The passenger fare category a flight search asks the supplier for; a special category needs each traveller's ID. */
public enum FareCategory {
    REGULAR,
    STUDENT,
    SENIOR_CITIZEN;

    /** An absent category is a Regular search. */
    public static FareCategory orRegular(FareCategory category) {
        return category == null ? REGULAR : category;
    }
}
