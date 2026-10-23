package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "New value for a platform setting")
public class PlatformSettingUpdateRequest {

    @NotBlank
    @Schema(description = "Raw value; validated per key", example = "2")
    private String value;
}
