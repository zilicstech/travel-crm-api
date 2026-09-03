package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "Writes immediately, no edit-mode round trip - a preference is one "
        + "conversation with the customer, ticked or unticked on the spot")
public class ServicePreferenceToggleRequest {

    @NotBlank(message = "Preference name is required")
    @Schema(example = "Window Seat")
    private String name;

    @NotNull(message = "on is required")
    @Schema(example = "true")
    private Boolean on;
}
