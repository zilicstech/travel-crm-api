package com.voyra.crm.dto;

import com.voyra.crm.enums.ProposalItemType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProposalItemResponse {

    private String id;
    private ProposalItemType type;
    private String description;
    private String supplier;
    private BigDecimal netCost;
    private BigDecimal sellingPrice;
    private BigDecimal marginPercent;
}
