package com.voyra.crm.enums;

/** What happened to the audited record. */
public enum AuditAction {
    CREATE, UPDATE, DELETE,
    /** A secret was shown in clear text (supplier credential reveal) - nothing changed. */
    REVEAL
}
