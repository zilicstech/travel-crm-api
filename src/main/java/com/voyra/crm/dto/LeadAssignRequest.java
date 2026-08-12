package com.voyra.crm.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class LeadAssignRequest {

    @NotBlank(message = "agentId is required")
    private String agentId;
}
