package com.voyra.crm.dto;

import com.voyra.crm.enums.AgencySettingKind;
import com.voyra.crm.enums.ServiceType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "One agency-configurable option")
public class AgencySettingResponse {

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String id;

    @Schema(example = "TRAVEL_CATEGORY")
    private AgencySettingKind kind;

    @Schema(example = "FLIGHT")
    private ServiceType serviceType;

    @Schema(example = "Cruise Package")
    private String name;

    @Schema(example = "true")
    private Boolean isActive;

    @Schema(description = "Seeded default rather than agency-added", example = "false")
    private Boolean isDefault;

    @Schema(example = "3")
    private Integer sortOrder;
}
