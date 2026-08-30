package com.voyra.crm.dto;

import com.voyra.crm.enums.ServiceType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

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

    @NotBlank(message = "Password is required")
    @Size(min = 8, message = "Password must be at least 8 characters")
    @Schema(description = "Login password, chosen by the Agency Owner and handed to the agent", example = "SecurePass123")
    private String password;

    @NotEmpty(message = "At least one manageable service is required")
    @Schema(description = "Which service types this agent may work on", example = "[\"FLIGHT\", \"HOTEL\"]")
    private List<ServiceType> manageableServices;
}
