package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Schema(description = "Patch semantics: a null field is left unchanged. agencyName/ownerName/"
        + "ownerEmail are not editable here - they are set at onboarding by the platform.")
public class AgencyProfileUpdateRequest {

    @Schema(example = "27AABCU9603R1ZM")
    private String gstNumber;

    @Schema(example = "42 MG Road, Bengaluru")
    private String address;

    @Schema(example = "₹ (INR)")
    private String currency;

    @Schema(example = "2.00")
    private BigDecimal defaultCommission;

    @Schema(description = "Opaque storage key from FileStorageService")
    private String logoKey;
}
