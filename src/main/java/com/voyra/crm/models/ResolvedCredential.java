package com.voyra.crm.models;

import com.voyra.crm.enums.SupplierEnvironment;
import com.voyra.crm.enums.SupplierProvider;

/** The decrypted platform credential a search provider calls with - in memory only, never returned from a controller. */
public record ResolvedCredential(SupplierProvider provider, SupplierEnvironment environment, String baseUrl,
                                 String apiKey, String userId) {

    /** Masked so a stray log line or exception message never carries the plaintext key. */
    @Override
    public String toString() {
        return "ResolvedCredential[provider=" + provider + ", environment=" + environment + ", baseUrl=" + baseUrl
                + ", apiKey=" + mask(apiKey) + ", userId=" + mask(userId) + "]";
    }

    private static String mask(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 4 ? "••••••" : "••••••" + value.substring(value.length() - 4);
    }
}
