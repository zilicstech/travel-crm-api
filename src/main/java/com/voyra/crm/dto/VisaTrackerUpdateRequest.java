package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** Patch semantics - only non-null fields are toggled, matching the UI's one-step-at-a-time checklist. */
@Data
@Schema(description = "Request body for toggling one or more steps of a Lead's embedded visa tracker")
public class VisaTrackerUpdateRequest {

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
