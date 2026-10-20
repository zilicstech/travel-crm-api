package com.voyra.crm.dto;

import com.voyra.crm.enums.MarkupMode;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.DecimalMin;
import java.math.BigDecimal;

@Data
@Schema(description = "Patch semantics: a null field is left unchanged. type and serviceId are "
        + "fixed at creation - remove and re-add the line to move it between services.")
public class ProposalItemUpdateRequest implements com.voyra.crm.models.ForeignPriced {

    @Schema(example = "5 nights at Burj Al Arab, Deluxe Suite")
    @jakarta.validation.constraints.Size(max = 255, message = "Description must be 255 characters or fewer")
    private String description;

    @Schema(example = "Cleartrip")
    private String supplier;

    @Schema(description = "Cost paid to the supplier", example = "18000.00")
    @jakarta.validation.constraints.DecimalMin(value = "0.0", message = "Net cost cannot be negative")
    private BigDecimal netCost;

    @Schema(description = "Price shown to the customer", example = "22000.00")
    @jakarta.validation.constraints.DecimalMin(value = "0.0", message = "Selling price cannot be negative")
    private BigDecimal sellingPrice;

    @Schema(description = "What the agent intended when setting sellingPrice", example = "PERCENT")
    private MarkupMode markupMode;

    @DecimalMin(value = "0.0", message = "markupValue cannot be negative")
    @Schema(description = "The markup value under markupMode - a percent or a flat rupee amount", example = "5.00")
    @jakarta.validation.constraints.DecimalMin(value = "0.0", message = "Markup cannot be negative")
    private BigDecimal markupValue;

    @jakarta.validation.constraints.Size(max = 3, message = "Purchase currency must be a 3-letter code")
    @Schema(description = "Currency the purchase price is in (ISO 4217); blank means the agency's base currency", example = "AED")
    private String purchaseCurrency;

    @jakarta.validation.constraints.DecimalMin(value = "0.0", message = "Purchase price cannot be negative")
    @Schema(description = "What the supplier charges, in purchaseCurrency. When set, netCost is worked out from it at purchaseRate", example = "1000.00")
    private java.math.BigDecimal purchasePrice;

    @jakarta.validation.constraints.DecimalMin(value = "0.0", message = "Purchase rate cannot be negative")
    @Schema(description = "What 1 unit of purchaseCurrency is worth in base on the purchase date; required when it isn't the base", example = "22.85")
    private java.math.BigDecimal purchaseRate;

    @jakarta.validation.constraints.Size(max = 3, message = "Markup currency must be a 3-letter code")
    @Schema(description = "Currency a FLAT markupInput is in (ISO 4217); blank means base", example = "AED")
    private String markupCurrency;

    @jakarta.validation.constraints.DecimalMin(value = "0.0", message = "Markup cannot be negative")
    @Schema(description = "A FLAT markup as typed, in markupCurrency. When set, markupValue is worked out from it at markupRate", example = "100.00")
    private java.math.BigDecimal markupInput;

    @jakarta.validation.constraints.DecimalMin(value = "0.0", message = "Markup rate cannot be negative")
    @Schema(description = "What 1 unit of markupCurrency is worth in base; required when it isn't the base", example = "22.85")
    private java.math.BigDecimal markupRate;
}
