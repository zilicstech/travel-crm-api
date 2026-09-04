package com.voyra.crm.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.voyra.crm.enums.ProposalItemType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A proposal line item on a Lead - never exposed to the public proposal endpoint")
public class ProposalItemResponse {

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String id;

    @Schema(example = "HOTEL")
    private ProposalItemType type;

    @Schema(description = "Null means a trip-level charge", example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String serviceId;

    @Schema(description = "Snapshot of the service's label when this line was added", example = "Hotel — Bangkok")
    private String serviceLabel;

    @Schema(example = "5 nights at Burj Al Arab, Deluxe Suite")
    private String description;

    @Schema(example = "Cleartrip")
    private String supplier;

    @Schema(description = "Cost paid to the supplier", example = "18000.00")
    private BigDecimal netCost;

    @Schema(description = "Price shown to the customer", example = "22000.00")
    private BigDecimal sellingPrice;

    @Schema(description = "Server-computed margin % for this line item", example = "18.2")
    private BigDecimal marginPercent;

    @Schema(description = "Null means a plain add-on that always counts. A shared value means "
            + "this line is one of several mutually-exclusive alternatives - exactly one line "
            + "per group is selected and counted.", example = "svc-flight-1")
    private String optionGroup;

    @JsonProperty("isSelected")
    @Schema(description = "Within an option group, whether this line currently counts toward the total")
    private boolean selected;

    @Schema(description = "Who selected this option: AGENT or CUSTOMER. Null until chosen.", example = "AGENT")
    private String selectedBy;

    @Schema(description = "When the selection was made")
    private LocalDateTime selectedAt;
}
