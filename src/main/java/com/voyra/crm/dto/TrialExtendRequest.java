package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "Extend (or restart) an agency's Suite trial")
public class TrialExtendRequest {

    @NotNull(message = "days is required")
    @Min(value = 1, message = "days must be at least 1")
    @Max(value = 90, message = "days cannot exceed 90")
    @Schema(description = "Days to add to the trial (from now if it has ended)", example = "14")
    private Integer days;
}
