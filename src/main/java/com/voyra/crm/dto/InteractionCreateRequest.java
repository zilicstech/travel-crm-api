package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "Request body for logging a customer interaction note")
public class InteractionCreateRequest {

    @NotBlank(message = "Note text is required")
    @Schema(example = "Called to confirm passport details, will follow up next week.")
    private String note;
}
