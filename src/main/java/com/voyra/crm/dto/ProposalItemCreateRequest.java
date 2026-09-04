package com.voyra.crm.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.voyra.crm.enums.ProposalItemType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Schema(description = "Request body for adding a proposal line item to a Lead")
public class ProposalItemCreateRequest {

    @NotNull(message = "Type is required")
    @Schema(example = "HOTEL")
    private ProposalItemType type;

    @Schema(description = "Ties this line to one service instance; omit for a trip-level charge",
            example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String serviceId;

    @NotBlank(message = "Description is required")
    @Schema(example = "5 nights at Burj Al Arab, Deluxe Suite")
    private String description;

    @Schema(example = "Cleartrip")
    private String supplier;

    @Schema(description = "Cost paid to the supplier, defaults to 0 if not supplied", example = "18000.00")
    private BigDecimal netCost;

    @Schema(description = "Price shown to the customer, defaults to 0 if not supplied", example = "22000.00")
    private BigDecimal sellingPrice;

    @Schema(description = "Omit for a plain add-on that always counts. Set to group this line with "
            + "other mutually-exclusive alternatives - typically the service id.", example = "svc-flight-1")
    private String optionGroup;

    @JsonProperty("isSelected")
    @Schema(description = "Only meaningful with optionGroup set. When true, every other line in the "
            + "same group is deselected in the same transaction.")
    private boolean selected;
}
