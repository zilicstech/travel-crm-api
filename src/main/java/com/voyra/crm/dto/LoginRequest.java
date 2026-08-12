package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "Login credentials")
public class LoginRequest {

    @NotBlank(message = "Email is required")
    @Schema(description = "Account email", example = "owner@globalexplorer.com")
    private String email;

    @NotBlank(message = "Password is required")
    @Schema(description = "Account password", example = "Passw0rd!")
    private String password;
}
