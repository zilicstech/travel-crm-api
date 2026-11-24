package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "The file held for one File field of a visa application")
public class VisaFileResponse {

    @Schema(example = "f9")
    private String fieldId;

    @Schema(example = "contract.pdf")
    private String fileName;

    @Schema(example = "application/pdf")
    private String fileType;

    @Schema(example = "Liam Smith")
    private String uploadedByName;

    @Schema(example = "2026-11-24T10:15:30")
    private LocalDateTime uploadedAt;
}
