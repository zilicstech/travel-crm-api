package com.voyra.crm.dto;

import com.voyra.crm.enums.ProposalItemType;
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
public class PublicProposalItemResponse {

    private ProposalItemType type;
    private String description;
    private String supplier;
    private BigDecimal sellingPrice;
}
