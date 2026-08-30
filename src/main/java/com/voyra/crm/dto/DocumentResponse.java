package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** Shared shape for member_documents rows. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "An uploaded document (passport, visa, aadhaar) belonging to a member")
public class DocumentResponse {

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String id;

    @Schema(description = "Original uploaded filename", example = "passport.pdf")
    private String name;

    @Schema(example = "Passport")
    private String docType;

    @Schema(example = "2026-08-13T09:15:22")
    private LocalDateTime uploadedDate;
}
