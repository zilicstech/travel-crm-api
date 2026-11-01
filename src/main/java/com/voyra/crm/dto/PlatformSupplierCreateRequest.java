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
@Schema(description = "Add a supplier - the base URL decides UAT or production")
public class PlatformSupplierCreateRequest {

    @NotBlank(message = "Name is required")
    @Size(max = 80, message = "Name must be at most 80 characters")
    @Schema(example = "Tripjack")
    private String name;

    @NotNull(message = "Integration is required")
    @Schema(example = "TRIPJACK")
    private SupplierProvider provider;

    @NotBlank(message = "Base URL is required")
    @Size(max = 255, message = "Base URL must be at most 255 characters")
    @Schema(example = "https://api.tripjack.com")
    private String baseUrl;

    @NotBlank(message = "API key is required")
    @Size(max = 500, message = "API key must be at most 500 characters")
    @Schema(example = "tj_live_key_123")
    private String apiKey;

    @NotEmpty(message = "Give at least one product")
    @Schema(description = "Search and booking switches per product")
    private List<@NotNull @Valid SupplierProductSettings> products;
}
