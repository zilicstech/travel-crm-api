package com.voyra.crm.dto;

import com.voyra.crm.enums.SupplierEnvironment;
import com.voyra.crm.enums.SupplierProvider;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "Create or update the agency's credentials for one supplier - an empty "
        + "apiKey on an update leaves the stored key unchanged, so the field is never sent back")
public class SupplierCredentialUpsertRequest {

    @NotNull(message = "Provider is required")
    @Schema(example = "TRIPJACK")
    private SupplierProvider provider;

    @NotNull(message = "Environment is required")
    @Schema(example = "UAT")
    private SupplierEnvironment environment;

    @NotBlank(message = "Base URL is required")
    @Schema(example = "https://apitest.tripjack.com")
    private String baseUrl;

    @Schema(description = "Leave blank on an update to keep the stored key unchanged", example = "tj_live_key_123")
    private String apiKey;

    @Schema(description = "Optional - some Tripjack products pair a user id with the key", example = "agency-001")
    private String userId;

    @Schema(example = "true")
    private Boolean isActive;
}
