package com.voyra.crm.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.voyra.crm.enums.ProposalItemType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Public-facing proposal line item. Deliberately excludes netCost/margin/selectedBy - this
 * is a hand-built DTO, never a mapped entity, so there is no risk of those fields leaking.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "One line item on a public, unauthenticated proposal view - pricing-safe by construction")
public class PublicProposalItemResponse {

    @Schema(description = "Needed so the customer's selection POST can reference this exact line", example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String id;

    @Schema(example = "HOTEL")
    private ProposalItemType type;

    @Schema(description = "Which service instance this line belongs to - undefined means a trip-level charge", example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String serviceId;

    @Schema(example = "Flight — Bangalore to Phuket")
    private String serviceLabel;

    @Schema(example = "5 nights at Burj Al Arab, Deluxe Suite")
    private String description;

    @Schema(example = "Cleartrip")
    private String supplier;

    @Schema(description = "Price shown to the customer - net cost is never exposed here", example = "22000.00")
    private BigDecimal sellingPrice;

    @Schema(description = "Null means a plain add-on that always counts. A shared value means "
            + "this line is one of several alternatives the customer can choose between.", example = "svc-flight-1")
    private String optionGroup;

    @JsonProperty("isSelected")
    @Schema(description = "Within an option group, which line currently counts toward the total")
    private boolean selected;
}
