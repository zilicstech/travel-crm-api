package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "Patch semantics: a null field is left unchanged. Edits the trip-level "
        + "facts captured on Add Lead step 2/4 - destination, budget and the one Special "
        + "Remarks / Requirements field - after the lead already exists.")
public class LeadDetailUpdateRequest {

    @Size(max = 150, message = "Destination must be 150 characters or fewer")
    @Schema(example = "Dubai, UAE")
    private String destination;

    @Size(max = 50, message = "Budget must be 50 characters or fewer")
    @Schema(example = "1,50,000 - 2,00,000")
    private String budget;

    @Schema(description = "One remark for the whole trip", example = "First time abroad, celebrating their anniversary")
    private String specialNotes;
}
