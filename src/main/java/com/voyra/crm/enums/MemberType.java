package com.voyra.crm.enums;

/**
 * Role of a member within its client.
 *
 * <p>CLIENT is the primary member - the person the client account represents and the agency's
 * point of contact. A partial unique index on {@code member (client_id) WHERE type = 'CLIENT'}
 * enforces exactly one per client, so deactivating or retyping the primary member without
 * nominating a replacement will fail at the database.
 */
public enum MemberType {
    CLIENT, MEMBER
}
