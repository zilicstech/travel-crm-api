package com.voyra.crm.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "A platform admin (SUPER_ADMIN) login")
public class PlatformAdminResponse {

    @Schema(example = "a1b2c3d4-0000-0000-0000-000000000001")
    private String id;

    @Schema(example = "Priya Nair")
    private String name;

    @Schema(example = "priya@voyra.in")
    private String email;

    @Schema(description = "False once deactivated: the login is refused", example = "true")
    private boolean active;

    @Schema(example = "2026-10-26T09:30:00")
    private LocalDateTime createdDate;

    @Schema(description = "The generated password - only in the response that adds the admin; hand it over securely",
            example = "k7Qm2xPa9RtZ4wHn")
    private String password;
}
