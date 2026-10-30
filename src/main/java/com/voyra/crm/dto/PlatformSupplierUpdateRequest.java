package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "Change a supplier's name, key or active flag - a blank apiKey keeps the stored key")
public class PlatformSupplierUpdateRequest {

    @NotBlank(message = "Name is required")
    @Size(max = 80, message = "Name must be at most 80 characters")
    @Schema(example = "Tripjack")
    private String name;

    @Size(max = 500, message = "API key must be at most 500 characters")
    @Schema(description = "Leave blank to keep the stored key", example = "tj_live_key_123")
    private String apiKey;

    @Schema(example = "true")
    private Boolean isActive;
}
