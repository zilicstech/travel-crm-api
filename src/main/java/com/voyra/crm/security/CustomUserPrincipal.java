package com.voyra.crm.security;

import com.voyra.crm.enums.UserType;

public record CustomUserPrincipal(
        String userId,
        String username,
        UserType userType,
        String tenantId
) {
    public boolean isSuperAdmin() {
        return userType == UserType.SUPER_ADMIN;
    }

    public boolean isAgencyOwner() {
        return userType == UserType.AGENCY_OWNER;
    }

    public boolean isAgent() {
        return userType == UserType.AGENT;
    }

    public boolean isAccountant() {
        return userType == UserType.ACCOUNTANT;
    }

    /** Both personas stored in the agent table - resolvable via agentRepository. */
    public boolean isStaffUser() {
        return isAgent() || isAccountant();
    }
}
