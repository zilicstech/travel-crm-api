package com.voyra.crm.dto;

import com.voyra.crm.enums.AgentDepartment;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/** Patch semantics - every field optional, only non-null fields are applied. */
@Data
@Schema(description = "Request body for updating an Agent's profile")
public class AgentUpdateRequest {

    private String name;
    private String phone;
    private AgentDepartment department;

    @Schema(description = "Percentage of booking profit paid as commission", example = "5.00")
    private BigDecimal commissionRate;
}
