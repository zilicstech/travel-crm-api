package com.voyra.crm.models;

import com.voyra.crm.enums.SupplierEnvironment;
import com.voyra.crm.enums.SupplierProvider;

/** The decrypted platform credential a search provider calls with - in memory only, never returned from a controller. */
public record ResolvedCredential(SupplierProvider provider, SupplierEnvironment environment, String baseUrl,
                                 String apiKey, String userId) {
}
