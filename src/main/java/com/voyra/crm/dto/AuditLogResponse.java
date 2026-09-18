package com.voyra.crm.dto;

import com.voyra.crm.enums.AuditAction;
import com.voyra.crm.enums.AuditEntityType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "One append-only audit trail entry")
public class AuditLogResponse {

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String id;

    @Schema(example = "BOOKING")
    private AuditEntityType entityType;

    @Schema(example = "9c4e1a2b-...")
    private String entityId;

    @Schema(description = "Human anchor so a listing stays readable after the record is gone", example = "Riya Shah / Bangkok")
    private String entityLabel;

    @Schema(example = "UPDATE")
    private AuditAction action;

    @Schema(example = "a1b2c3d4-...")
    private String actorId;

    @Schema(example = "Liam Fernandes")
    private String actorName;

    @Schema(description = "Empty for CREATE; the final snapshot for DELETE; the diff for UPDATE")
    private List<AuditChange> changes;

    @Schema(example = "2026-09-18T10:15:30")
    private LocalDateTime createdAt;
}
