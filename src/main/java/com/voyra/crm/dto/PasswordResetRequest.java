package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "Agency Owner resets an Agent's or Accountant's login password")
public class PasswordResetRequest {

    @NotBlank(message = "New password is required")
    @Size(min = 8, message = "Password must be at least 8 characters")
    @Schema(example = "NewSecurePass123")
    private String newPassword;

    @NotBlank(message = "Please confirm the new password")
    @Schema(description = "Must match newPassword exactly", example = "NewSecurePass123")
    private String confirmPassword;
}
