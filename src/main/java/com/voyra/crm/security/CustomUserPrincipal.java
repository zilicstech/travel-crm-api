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
}
