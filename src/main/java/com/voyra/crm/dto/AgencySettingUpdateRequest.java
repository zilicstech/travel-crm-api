package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "Patch semantics: a null field is left unchanged")
public class AgencySettingUpdateRequest {

    @Schema(example = "Cruise Package")
    private String name;

    @Schema(example = "true")
    private Boolean isActive;

    @Schema(example = "3")
    private Integer sortOrder;
}
