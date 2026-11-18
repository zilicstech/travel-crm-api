package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Why Voyra declines a cancellation request; shown to the agency")
public record CancelRequestRejectRequest(
        @Schema(description = "Reason shown to the agency", example = "This fare cannot be cancelled after departure")
        @NotBlank(message = "A note is required") @Size(max = 1000, message = "The note can be at most 1000 characters") String note) {
}
