package com.voyra.crm.dto;

import com.voyra.crm.enums.ProposalItemType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Public-facing proposal line item. Deliberately excludes netCost/margin - this is a
 * hand-built DTO, never a mapped entity, so there is no risk of those fields leaking.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "One line item on a public, unauthenticated proposal view - pricing-safe by construction")
public class PublicProposalItemResponse {

    @Schema(example = "HOTEL")
    private ProposalItemType type;

    @Schema(example = "5 nights at Burj Al Arab, Deluxe Suite")
    private String description;

    @Schema(example = "Cleartrip")
    private String supplier;

    @Schema(description = "Price shown to the customer - net cost is never exposed here", example = "22000.00")
    private BigDecimal sellingPrice;
}
