package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Result of onboarding a new Agency - carries the generated Owner password once, on creation only")
public class AgencyCreateResponse {

    private String id;
    private String agencyName;
    private String ownerName;
    private String ownerEmail;

    @Schema(description = "Generated Owner login password. Also retrievable later via the credentials endpoint.")
    private String initialPassword;
}
