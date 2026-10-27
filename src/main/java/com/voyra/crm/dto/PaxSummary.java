package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
@Schema(description = "A traveller as the agency sees them back: name and type only, never ID numbers")
public class PaxSummary {

    @Schema(description = "Title and name as booked", example = "Mr Rahul Sharma")
    private String name;

    @Schema(description = "ADULT, CHILD or INFANT", example = "ADULT")
    private String type;
}
