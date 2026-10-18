package com.voyra.crm.dto;

import com.voyra.crm.enums.MarkupMode;
import com.voyra.crm.enums.ProposalItemType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import jakarta.validation.constraints.DecimalMin;
import java.math.BigDecimal;

@Data
@Schema(description = "One option line within a batch proposal-item create")
public class ProposalItemBatchLine {

    @NotNull(message = "Type is required")
    @Schema(example = "FLIGHT")
    private ProposalItemType type;

    @NotBlank(message = "Description is required")
    @Schema(example = "IndiGo 6E-204, 06:15 Bangalore to Phuket, non-stop")
    @jakarta.validation.constraints.Size(max = 255, message = "Description must be 255 characters or fewer")
    private String description;

    @Schema(example = "Tripjack")
    private String supplier;

    @Schema(description = "Cost paid to the supplier, defaults to 0 if not supplied", example = "18000.00")
    @jakarta.validation.constraints.DecimalMin(value = "0.0", message = "Net cost cannot be negative")
    private BigDecimal netCost;

    @Schema(description = "Price shown to the customer, defaults to 0 if not supplied", example = "22000.00")
    @jakarta.validation.constraints.DecimalMin(value = "0.0", message = "Selling price cannot be negative")
    private BigDecimal sellingPrice;

    @Schema(description = "What the agent intended when setting sellingPrice - omit for a line typed straight in with no markup calculator", example = "PERCENT")
    private MarkupMode markupMode;

    @DecimalMin(value = "0.0", message = "markupValue cannot be negative")
    @Schema(description = "The markup value under markupMode - a percent or a flat rupee amount", example = "5.00")
    @jakarta.validation.constraints.DecimalMin(value = "0.0", message = "Markup cannot be negative")
    private BigDecimal markupValue;
}
