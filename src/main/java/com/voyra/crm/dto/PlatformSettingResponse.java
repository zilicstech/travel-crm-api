package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
@Schema(description = "A platform-wide setting")
public class PlatformSettingResponse {

    @Schema(example = "tds_rate")
    private String key;

    @Schema(example = "2")
    private String value;

    @Schema(example = "2026-10-23T09:30:00Z")
    private Instant updatedAt;
}
