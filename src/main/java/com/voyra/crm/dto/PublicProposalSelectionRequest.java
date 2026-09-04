package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "The customer's chosen option line per service - one id per option group they are deciding on")
public class PublicProposalSelectionRequest {

    @NotEmpty(message = "At least one selection is required")
    @Schema(description = "Proposal item ids to select - each must belong to this proposal and be an option line")
    private List<String> selectedItemIds;
}
