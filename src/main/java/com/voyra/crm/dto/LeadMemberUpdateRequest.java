package com.voyra.crm.dto;

import com.voyra.crm.enums.LeadMemberStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "Patch semantics: a null field means 'not supplied' and is left unchanged. "
        + "Removing a traveller from a lead is done by setting status to DROPPED, never by "
        + "deleting - the document checklist collected before they pulled out has to survive.")
public class LeadMemberUpdateRequest {

    @Schema(description = "DROPPED requires droppedReason", example = "CONFIRMED")
    private LeadMemberStatus status;

    @Size(max = 255, message = "Dropped reason must be 255 characters or fewer")
    @Schema(description = "Required when status is DROPPED", example = "Visa rejected, travelling separately")
    private String droppedReason;

    @Schema(description = "Per-traveller document checklist; each traveller in a group is tracked separately")
    private Boolean passportCollected;

    @Schema(description = "Photos meeting the destination's specification have been received")
    private Boolean photosCollected;

    @Schema(description = "Visa application forms completed for this traveller")
    private Boolean formsFilled;

    @Schema(description = "This traveller's application has been lodged with the embassy")
    private Boolean submittedToEmbassy;

    @Schema(description = "Visa granted for this traveller")
    private Boolean visaApproved;
}
