package com.voyra.crm.enums;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

public enum UserType {

    SUPER_ADMIN, AGENCY_OWNER, AGENT;

    public String getAuthority() {
        return "ROLE_" + this.name();
    }

    public GrantedAuthority toGrantedAuthority() {
        return new SimpleGrantedAuthority(getAuthority());
    }

    /** Lenient parse for tokens: unknown/blank falls back to the least-privileged role. */
    public static UserType fromString(String value) {
        if (value == null || value.isBlank()) {
            return AGENT;
        }
        try {
            return UserType.valueOf(value.toUpperCase().replace("ROLE_", ""));
        } catch (IllegalArgumentException e) {
            return AGENT;
        }
    }
}
