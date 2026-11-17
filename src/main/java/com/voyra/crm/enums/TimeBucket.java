package com.voyra.crm.enums;

import java.time.LocalTime;

/** The four six-hour windows of a day the flight filters offer. */
public enum TimeBucket {
    /** 00:00-05:59 */
    EARLY,
    /** 06:00-11:59 */
    MORNING,
    /** 12:00-17:59 */
    AFTERNOON,
    /** 18:00-23:59 */
    EVENING;

    public static TimeBucket of(LocalTime time) {
        return values()[time.getHour() / 6];
    }
}
