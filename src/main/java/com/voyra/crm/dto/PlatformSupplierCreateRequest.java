package com.voyra.crm.dto;

import com.voyra.crm.enums.SupplierProvider;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "Add a supplier - production only: the base URL and environment come from the integration")
public class PlatformSupplierCreateRequest {

    @NotBlank(message = "Name is required")
    @Size(max = 80, message = "Name must be at most 80 characters")
    @Schema(example = "Tripjack")
    private String name;

    @NotNull(message = "Integration is required")
    @Schema(example = "TRIPJACK")
    private SupplierProvider provider;

    @NotBlank(message = "API key is required")
    @Size(max = 500, message = "API key must be at most 500 characters")
    @Schema(example = "tj_live_key_123")
    private String apiKey;

    @Schema(description = "Whether every agency's searches and bookings use it", example = "true")
    private Boolean isActive;

    @Valid
    @Schema(description = "Commission terms per product; a product left out gets the 5% default")
    private List<SupplierProductTerms> products;
}
