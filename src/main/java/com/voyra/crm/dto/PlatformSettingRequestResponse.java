package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
@Schema(description = "A platform setting change waiting for (or decided by) a second platform admin")
public class PlatformSettingRequestResponse {

    @Schema(example = "r1d2e3f4-0000-0000-0000-000000000001")
    private String id;

    @Schema(example = "tripjack_live_ready")
    private String settingKey;

    @Schema(example = "true")
    private String value;

    @Schema(description = "PENDING, APPROVED or WITHDRAWN", example = "PENDING")
    private String status;

    @Schema(description = "Platform admin who asked for the change")
    private String requestedBy;

    @Schema(example = "2026-10-26T09:30:00Z")
    private Instant requestedAt;

    @Schema(description = "Platform admin who approved or withdrew it")
    private String decidedBy;

    @Schema(example = "2026-10-26T10:00:00Z")
    private Instant decidedAt;
}
