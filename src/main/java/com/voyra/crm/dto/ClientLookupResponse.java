package com.voyra.crm.dto;

import com.voyra.crm.enums.ClientType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Result of the Add Lead wizard's duplicate check, so a walk-in already "
        + "known to the agency is found instead of being created twice.")
public class ClientLookupResponse {

    @Schema(description = "False when no active client carries this identifier")
    private boolean found;

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String id;

    @Schema(example = "9876543210")
    private String identifier;

    @Schema(example = "Ajay Sharma")
    private String name;

    @Schema(example = "B2C")
    private ClientType type;

    @Schema(description = "Active members available to pick as travellers")
    private Integer memberCount;
}
