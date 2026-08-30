package com.voyra.crm.dto;

import com.voyra.crm.enums.ServiceType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** Patch semantics - every field optional, only non-null fields are applied. */
@Data
@Schema(description = "Request body for updating an Agent's profile")
public class AgentUpdateRequest {

    @Size(max = 150, message = "Name must be 150 characters or fewer")
    @Schema(example = "Liam Smith")
    private String name;

    @Size(max = 20, message = "Phone must be 20 characters or fewer")
    @Schema(example = "+1 555 123 4567")
    private String phone;

    @DecimalMin(value = "0.00", message = "Commission rate cannot be negative")
    @DecimalMax(value = "100.00", message = "Commission rate cannot exceed 100")
    @Schema(description = "Percentage of booking profit paid as commission", example = "5.00")
    private BigDecimal commissionRate;

    @Schema(description = "Which service types this agent may work on. Null leaves it unchanged; an empty list clears it.", example = "[\"FLIGHT\", \"HOTEL\"]")
    private List<ServiceType> manageableServices;
}
