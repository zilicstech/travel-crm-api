package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "Request body for adding a note to a Lead")
public class LeadNoteCreateRequest {

    @NotBlank(message = "Note text is required")
    @Schema(example = "Sent a revised proposal with a lower-cost hotel option.")
    private String text;
}
