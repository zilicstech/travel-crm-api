package com.voyra.crm.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VisaTrackerResponse {

    private Boolean passportCollected;
    private Boolean photosCollected;
    private Boolean formsFilled;
    private Boolean submittedToEmbassy;
    private Boolean approved;
}
