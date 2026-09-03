package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "The signed-in owner's own agency profile - name/owner/email are set at "
        + "onboarding and are read-only here; gstNumber/address/currency/defaultCommission/logoKey "
        + "are the fields this endpoint can change.")
public class AgencyProfileResponse {

    @Schema(example = "Global Explorer Travels")
    private String agencyName;

    @Schema(example = "John Davis")
    private String ownerName;

    @Schema(example = "owner@globalexplorer.com")
    private String ownerEmail;

    @Schema(example = "27AABCU9603R1ZM")
    private String gstNumber;

    @Schema(example = "42 MG Road, Bengaluru")
    private String address;

    @Schema(example = "₹ (INR)")
    private String currency;

    @Schema(description = "Default commission % applied to a newly created agent", example = "2.00")
    private BigDecimal defaultCommission;

    @Schema(description = "Opaque storage key from FileStorageService, never a filesystem path")
    private String logoKey;
}
