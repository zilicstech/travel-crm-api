package com.voyra.crm.dto;

import com.voyra.crm.enums.AgencySettingKind;
import com.voyra.crm.enums.ServiceType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "Adds one agency-configurable option - a lead source, a travel category, "
        + "a document type, or one service's preference. serviceType is required only when kind "
        + "is SERVICE_PREFERENCE.")
public class AgencySettingCreateRequest {

    @NotNull(message = "Kind is required")
    @Schema(example = "TRAVEL_CATEGORY")
    private AgencySettingKind kind;

    @Schema(description = "Required when kind is SERVICE_PREFERENCE", example = "FLIGHT")
    private ServiceType serviceType;

    @NotBlank(message = "Name is required")
    @Schema(example = "Cruise Package")
    private String name;
}
