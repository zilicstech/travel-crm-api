package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "Toggles one step of one traveller's checklist on this Visa service. "
        + "Merges into visa_checklists[memberId] - never touches lead_members.")
public class VisaChecklistToggleRequest {

    @NotBlank(message = "memberId is required")
    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String memberId;

    @NotBlank(message = "key is required")
    @Schema(description = "One of passportCollected, photosCollected, formsFilled, "
            + "submittedToEmbassy, approved, passportReturned", example = "passportCollected")
    private String key;

    @NotNull(message = "value is required")
    @Schema(example = "true")
    private Boolean value;
}
