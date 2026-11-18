package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A seat selected for one traveller on one flight segment")
public class PaxSeatSelection {

    @Size(max = 80)
    @Schema(description = "Segment key from the flight seat map", example = "1490")
    private String segmentKey;

    @Size(max = 80)
    @Schema(description = "Supplier seat code from the seat map", example = "12A")
    private String code;

    @Size(max = 20)
    @Schema(description = "Human-readable seat number printed on the ticket", example = "12A")
    private String seatNo;
}
