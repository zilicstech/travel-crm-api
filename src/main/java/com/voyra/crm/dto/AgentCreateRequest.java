package com.voyra.crm.dto;

import com.voyra.crm.enums.AgentDepartment;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "Request body for adding a new Agent")
public class AgentCreateRequest {

    @NotBlank(message = "Name is required")
    @Schema(example = "Liam Smith")
    private String name;

    @NotBlank(message = "Email is required")
    @Email(message = "Email must be a valid email address")
    @Schema(example = "liam@globalexplorer.com")
    private String email;

    @Schema(example = "+1 555 123 4567")
    private String phone;

    @NotNull(message = "Department is required")
    @Schema(description = "Agent's department", example = "SALES")
    private AgentDepartment department;
}
