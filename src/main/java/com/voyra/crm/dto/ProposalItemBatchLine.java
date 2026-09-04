package com.voyra.crm.dto;

import com.voyra.crm.enums.ProposalItemType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Schema(description = "One option line within a batch proposal-item create")
public class ProposalItemBatchLine {

    @NotNull(message = "Type is required")
    @Schema(example = "FLIGHT")
    private ProposalItemType type;

    @NotBlank(message = "Description is required")
    @Schema(example = "IndiGo 6E-204, 06:15 Bangalore to Phuket, non-stop")
    private String description;

    @Schema(example = "Tripjack")
    private String supplier;

    @Schema(description = "Cost paid to the supplier, defaults to 0 if not supplied", example = "18000.00")
    private BigDecimal netCost;

    @Schema(description = "Price shown to the customer, defaults to 0 if not supplied", example = "22000.00")
    private BigDecimal sellingPrice;
}
