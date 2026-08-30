package com.voyra.crm.enums;

/**
 * How a member relates to their client.
 *
 * <p>SELF belongs to the primary member. FRIEND and COLLEAGUE exist because a B2B group client
 * (a WhatsApp group, a corporate booking) has members with no family tie - the same roster
 * table serves both B2C families and B2B groups.
 */
public enum MemberRelation {
    SELF, SPOUSE, SON, DAUGHTER, PARENT, SIBLING, FRIEND, COLLEAGUE, OTHER
}
