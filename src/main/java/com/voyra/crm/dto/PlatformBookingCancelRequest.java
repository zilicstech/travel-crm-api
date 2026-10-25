package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "Cancel a ticketed booking")
public class PlatformBookingCancelRequest {

    @NotBlank
    @Size(min = 8, max = 80)
    @Schema(description = "Client-generated id; a double click with the same id cancels once", example = "5b1f0c1e-9a3d-4c55-8d0e-2f6c1a7b9e11")
    private String idempotencyKey;
}
