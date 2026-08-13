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
@Schema(description = "Traveller headcount and special requirements for a Lead")
public class GuestDetails {

    @Schema(description = "Defaults to 1 if not supplied", example = "2")
    private Integer adults;

    @Schema(example = "1")
    private Integer children;

    @Schema(example = "0")
    private Integer infants;

    @Schema(example = "Wheelchair assistance required")
    private String specialRequirements;
}
