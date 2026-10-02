package com.voyra.crm.dto;

import com.voyra.crm.enums.JournalPurpose;
import com.voyra.crm.enums.JournalSourceType;
import com.voyra.crm.enums.JournalStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A posted journal entry with its lines. Append-only - a correction is a reversal, never an edit.")
public class JournalEntryResponse {

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String id;

    @Schema(example = "JNV/2026-27/0001")
    private String entryNumber;

    @Schema(example = "2026-09-30")
    private LocalDate entryDate;

    @Schema(example = "2026-27")
    private String financialYear;

    @Schema(example = "MANUAL")
    private JournalSourceType sourceType;

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String sourceId;

    @Schema(example = "MANUAL_ENTRY")
    private JournalPurpose purpose;

    @Schema(example = "Correcting entry per accountant's note")
    private String narration;

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String bookingId;

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String branchId;

    @Schema(example = "POSTED")
    private JournalStatus status;

    @Schema(description = "Set only on the new entry a reversal creates", example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String reversesEntryId;

    @Schema(example = "2026-09-30T18:05:00")
    private LocalDateTime createdAt;

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String createdBy;

    @Schema(description = "This entry's debit and credit lines, in line order")
    private List<JournalLineResponse> lines;
}
