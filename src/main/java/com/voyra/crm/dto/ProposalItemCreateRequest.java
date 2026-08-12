package com.voyra.crm.dto;

import com.voyra.crm.enums.ProposalItemType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class ProposalItemCreateRequest {

    @NotNull(message = "Type is required")
    private ProposalItemType type;

    @NotBlank(message = "Description is required")
    private String description;

    private String supplier;
    private BigDecimal netCost;
    private BigDecimal sellingPrice;
}
