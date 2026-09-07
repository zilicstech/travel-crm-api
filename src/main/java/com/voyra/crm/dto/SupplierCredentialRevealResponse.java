package com.voyra.crm.dto;

import com.voyra.crm.enums.SupplierEnvironment;
import com.voyra.crm.enums.SupplierProvider;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Decrypted supplier credentials - owner-only, every retrieval is logged")
public class SupplierCredentialRevealResponse {

    @Schema(example = "TRIPJACK")
    private SupplierProvider provider;

    @Schema(example = "UAT")
    private SupplierEnvironment environment;

    @Schema(example = "https://apitest.tripjack.com")
    private String baseUrl;

    @Schema(description = "Decrypted API key", example = "tj_live_key_123")
    private String apiKey;

    @Schema(description = "Decrypted user id, when one was stored", example = "agency-001")
    private String userId;
}
