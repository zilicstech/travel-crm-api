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
@Schema(description = "Minimal agent identity for teammate-addressing pickers (e.g. shift handover) - "
        + "deliberately excludes performance/commission fields the full agent roster carries, "
        + "so any signed-in agent may list it, not just the owner")
public class TeammateResponse {

    @Schema(description = "Agent id", example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String id;

    @Schema(description = "Agent's display name", example = "Liam Smith")
    private String name;

    @Schema(description = "Department/desk label", example = "Flights Desk")
    private String department;
}
