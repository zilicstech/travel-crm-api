package com.voyra.crm.dto;

import com.voyra.crm.enums.SupplierProvider;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "Change a supplier's name, base URL, key or product switches - a blank apiKey keeps the stored key")
public class PlatformSupplierUpdateRequest {

    @NotBlank(message = "Name is required")
    @Size(max = 80, message = "Name must be at most 80 characters")
    @Schema(example = "Tripjack")
    private String name;

    @Schema(description = "Optional; when sent it must be the stored integration", example = "TRIPJACK")
    private SupplierProvider provider;

    @NotBlank(message = "Base URL is required")
    @Size(max = 255, message = "Base URL must be at most 255 characters")
    @Schema(example = "https://api.tripjack.com")
    private String baseUrl;

    @Size(max = 500, message = "API key must be at most 500 characters")
    @Schema(description = "Leave blank to keep the stored key", example = "tj_live_key_123")
    private String apiKey;

    @NotEmpty(message = "Give at least one product")
    @Schema(description = "Replaces the stored set: a product left out is removed")
    private List<@NotNull @Valid SupplierProductSettings> products;
}
