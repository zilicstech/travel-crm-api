package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One traveller's checklist on one Visa service, stored as the value in
 * {@code lead_service.visa_checklists} (JSONB, keyed by member_id). Replaces the five booleans
 * that used to live once per lead on {@code lead_members} - a lead with two Visa services (two
 * countries) needs two independent sets of these per traveller, not one shared set.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Per-traveller, per-visa-service document checklist")
public class VisaChecklistEntry {

    @Schema(example = "true")
    @Builder.Default
    private Boolean passportCollected = false;

    @Schema(example = "true")
    @Builder.Default
    private Boolean photosCollected = false;

    @Schema(example = "false")
    @Builder.Default
    private Boolean formsFilled = false;

    @Schema(example = "false")
    @Builder.Default
    private Boolean submittedToEmbassy = false;

    @Schema(example = "false")
    @Builder.Default
    private Boolean approved = false;

    @Schema(example = "false")
    @Builder.Default
    private Boolean passportReturned = false;
}
