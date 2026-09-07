package com.voyra.crm.dto;

import com.voyra.crm.enums.SupplierEnvironment;
import com.voyra.crm.enums.SupplierProvider;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Supplier credential status - the key is always masked here, never plaintext")
public class SupplierCredentialResponse {

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String id;

    @Schema(example = "TRIPJACK")
    private SupplierProvider provider;

    @Schema(example = "UAT")
    private SupplierEnvironment environment;

    @Schema(example = "https://apitest.tripjack.com")
    private String baseUrl;

    @Schema(description = "Last few characters only", example = "••••••3f2a")
    private String maskedApiKey;

    @Schema(example = "true")
    private Boolean isActive;

    @Schema(example = "2026-09-07T10:15:00")
    private LocalDateTime updatedAt;
}
