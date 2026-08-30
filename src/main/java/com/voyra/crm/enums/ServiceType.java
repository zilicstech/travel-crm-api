package com.voyra.crm.enums;

/**
 * The kind of work an Agent may be trusted with, and (per the lead management LLD) the kind
 * of service a Lead can hold any number of instances of. Fixed system data - the agency picks
 * which types a given Agent covers, it does not invent new ones.
 */
public enum ServiceType {
    FLIGHT, HOTEL, VISA, TRANSFER
}
