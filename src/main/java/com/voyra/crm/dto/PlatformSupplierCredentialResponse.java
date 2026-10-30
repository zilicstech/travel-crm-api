package com.voyra.crm.dto;

import com.voyra.crm.enums.SupplierEnvironment;
import com.voyra.crm.enums.SupplierProvider;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Platform supplier credential status - the key is always masked, never plaintext")
public class PlatformSupplierCredentialResponse {

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String id;

    @Schema(example = "Tripjack")
    private String name;

    @Schema(description = "The integration this supplier runs on", example = "TRIPJACK")
    private SupplierProvider provider;

    @Schema(description = "Read-only: new suppliers are PROD", example = "PROD")
    private SupplierEnvironment environment;

    @Schema(description = "Last four characters only", example = "••••••3f2a")
    private String maskedApiKey;

    @Schema(example = "true")
    private Boolean isActive;

    @Schema(description = "Commission terms per product")
    private List<SupplierProductTerms> products;

    @Schema(example = "2026-10-23T10:15:00Z")
    private Instant updatedAt;

    @Schema(description = "Super admin who last saved it", example = "a1b2c3d4-0000-0000-0000-000000000001")
    private String updatedBy;
}
