package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Traveller headcount as quoted by the client. This is what was said on "
        + "the phone; who is actually on the ticket is the lead's traveller manifest. The two "
        + "are allowed to disagree while an enquiry is still forming.")
public class GuestDetails {

    @Schema(description = "Defaults to 1 if not supplied", example = "2")
    private Integer adults;

    @Schema(description = "Children of any age; supply their ages in kidAges", example = "2")
    private Integer kids;

    @Schema(description = "Age of each child in years, in the order the client listed them. "
            + "Drives the derived infant count and the initial fare mix.")
    private List<Integer> kidAges;

    @Schema(description = "Derived from kidAges: children under 2. Read-only - supplying it has no effect.",
            example = "1")
    private Integer infants;

    @Schema(description = "Server-computed as adults + kids", example = "4")
    private Integer totalTravellers;
}
