package com.voyra.crm.dto;

import lombok.Data;

/** Patch semantics - only non-null fields are toggled, matching the UI's one-step-at-a-time checklist. */
@Data
public class VisaTrackerUpdateRequest {

    private Boolean passportCollected;
    private Boolean photosCollected;
    private Boolean formsFilled;
    private Boolean submittedToEmbassy;
    private Boolean approved;
}
