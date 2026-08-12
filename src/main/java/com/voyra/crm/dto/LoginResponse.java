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

    private boolean success;
    private String message;
    private String userId;
    private String name;
    private String role;
    private String tenantId;
    private String token;

    public static LoginResponse failure(String message) {
        return LoginResponse.builder().success(false).message(message).build();
    }
}
