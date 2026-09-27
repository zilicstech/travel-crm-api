package com.voyra.crm.dto;

import com.voyra.crm.enums.MarkupMode;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Schema(description = "Patch semantics: a null field is left unchanged. type and serviceId are "
        + "fixed at creation - remove and re-add the line to move it between services.")
public class ProposalItemUpdateRequest {

    @Schema(example = "5 nights at Burj Al Arab, Deluxe Suite")
    private String description;

    @Schema(example = "Cleartrip")
    private String supplier;

    @Schema(description = "Cost paid to the supplier", example = "18000.00")
    private BigDecimal netCost;

    @Schema(description = "Price shown to the customer", example = "22000.00")
    private BigDecimal sellingPrice;

    @Schema(description = "What the agent intended when setting sellingPrice", example = "PERCENT")
    private MarkupMode markupMode;

    @Schema(description = "The markup value under markupMode - a percent or a flat rupee amount", example = "5.00")
    private BigDecimal markupValue;
}
