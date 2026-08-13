package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "The lightweight 5-step visa tracker embedded on a Lead. Only present when VISA is in the lead's categories.")
public class VisaTrackerResponse {

    @Schema(example = "true")
    private Boolean passportCollected;

    @Schema(example = "true")
    private Boolean photosCollected;

    @Schema(example = "false")
    private Boolean formsFilled;

    @Schema(example = "false")
    private Boolean submittedToEmbassy;

    @Schema(example = "false")
    private Boolean approved;
}
