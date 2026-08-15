package com.voyra.crm.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Login result")
public class LoginResponse {

    @Schema(description = "False on failure - always the same generic failure for a bad email, wrong password, or inactive account")
    private boolean success;

    @Schema(example = "Login successful")
    private String message;

    @Schema(description = "The authenticated principal's id", example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String userId;

    @Schema(example = "Liam Smith")
    private String name;

    @Schema(example = "AGENT")
    private String role;

    @Schema(description = "Omitted for SUPER_ADMIN", example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String tenantId;

    @Schema(description = "Bearer JWT - pass as 'Authorization: Bearer <token>' on subsequent requests")
    private String token;

    public static LoginResponse failure(String message) {
        return LoginResponse.builder().success(false).message(message).build();
    }
}
