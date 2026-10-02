package com.voyra.crm.enums;

/** POSTED is permanent. A correction never edits a POSTED entry - it is reversed, which posts a new entry and marks the original REVERSED. */
public enum JournalStatus {
    POSTED, REVERSED
}
