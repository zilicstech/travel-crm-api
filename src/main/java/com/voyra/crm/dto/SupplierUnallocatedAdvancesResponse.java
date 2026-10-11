package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A vendor's unapplied advance pool - the remaining total plus every still-active advance row that funds it")
public class SupplierUnallocatedAdvancesResponse {

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String vendorId;

    @Schema(example = "Novotel Goa")
    private String vendorName;

    @Schema(description = "Sum of active advances minus sum of amounts already applied from them", example = "22000.00")
    private BigDecimal remainingAmount;

    @Schema(description = "Still-active advance payments that fund the remaining pool")
    private List<SupplierAdvanceRowResponse> advances;
}
