package com.voyra.crm.dto;

import com.voyra.crm.enums.JournalPurpose;
import com.voyra.crm.enums.JournalSourceType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

@Data
@Schema(description = "Posts one balanced double-entry journal. Debits must equal credits across the lines exactly, "
        + "with no tolerance - an unbalanced request is rejected, never rounded or auto-corrected.")
public class JournalPostRequest {

    @NotNull(message = "Entry date is required")
    @Schema(example = "2026-09-30")
    private LocalDate entryDate;

    @NotNull(message = "Source type is required")
    @Schema(example = "MANUAL")
    private JournalSourceType sourceType;

    @Schema(description = "Null only when sourceType is MANUAL", example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String sourceId;

    @NotNull(message = "Purpose is required")
    @Schema(example = "MANUAL_ENTRY")
    private JournalPurpose purpose;

    @NotBlank(message = "Narration is required")
    @Schema(example = "Correcting entry per accountant's note")
    private String narration;

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String bookingId;

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String branchId;

    @NotNull(message = "At least two lines are required")
    @Size(min = 2, message = "A journal entry needs at least two lines")
    @Valid
    @Schema(description = "At least two lines; total debits must equal total credits")
    private List<JournalLineRequest> lines;
}
