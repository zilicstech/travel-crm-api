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
@Schema(description = "A logged customer interaction note")
public class InteractionResponse {

    @Schema(example = "I8K3L7")
    private String id;

    @Schema(example = "CB9Y0N")
    private String authorAgentId;

    @Schema(description = "Denormalized snapshot, live-synced on agent rename", example = "Liam Smith")
    private String authorName;

    @Schema(example = "Called to confirm passport details, will follow up next week.")
    private String note;

    @Schema(example = "2026-08-13T09:15:22")
    private LocalDateTime createdDate;
}
