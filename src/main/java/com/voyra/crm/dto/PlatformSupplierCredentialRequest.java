package com.voyra.crm.dto;

import com.voyra.crm.enums.SupplierEnvironment;
import com.voyra.crm.enums.SupplierProvider;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "Set the platform's credential for one supplier - an empty apiKey on an update keeps "
        + "the stored key, so the key is never sent back to be re-submitted")
public class PlatformSupplierCredentialRequest {

    @NotNull(message = "Provider is required")
    @Schema(example = "TRIPJACK")
    private SupplierProvider provider;

    @NotNull(message = "Environment is required")
    @Schema(example = "UAT")
    private SupplierEnvironment environment;

    @NotBlank(message = "Base URL is required")
    @Size(max = 255, message = "Base URL must be at most 255 characters")
    @Schema(example = "https://apitest.tripjack.com")
    private String baseUrl;

    @Schema(description = "Required on first save; leave blank on an update to keep the stored key", example = "tj_live_key_123")
    private String apiKey;

    @Schema(description = "Optional - some Tripjack products pair a user id with the key", example = "voyra-001")
    private String userId;

    @Schema(description = "Activating this credential deactivates any other for the provider", example = "true")
    private Boolean isActive;
}
