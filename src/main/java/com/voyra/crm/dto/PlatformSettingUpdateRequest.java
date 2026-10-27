package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "New value for a platform setting")
public class PlatformSettingUpdateRequest {

    @NotNull
    @Schema(description = "Raw value; validated per key. Blank only where the key allows it (voyra_state_code: unset)", example = "2")
    private String value;
}
