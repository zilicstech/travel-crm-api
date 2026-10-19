package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "Patch semantics: a null field is left unchanged")
public class AgencySettingUpdateRequest {

    @Size(max = 150, message = "Name must be 150 characters or fewer")
    @Schema(example = "Cruise Package")
    private String name;

    @Schema(example = "true")
    private Boolean isActive;

    @Schema(example = "3")
    private Integer sortOrder;
}
