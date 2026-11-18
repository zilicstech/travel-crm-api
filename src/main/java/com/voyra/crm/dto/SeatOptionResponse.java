package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "One aircraft seat in a segment's cabin grid")
public class SeatOptionResponse {

    @Schema(description = "Displayed seat label", example = "12A")
    private String seatNo;

    @Schema(description = "Supplier seat code sent when confirming", example = "12A")
    private String code;

    @Schema(description = "1-based cabin row position", example = "12")
    private int row;

    @Schema(description = "1-based cabin column position", example = "1")
    private int column;

    @Schema(description = "True when the seat is already occupied or blocked", example = "false")
    private boolean booked;

    @Schema(description = "Seat charge in INR (0.00 for complimentary seats)", example = "0.00")
    private BigDecimal amount;

    @Schema(description = "True for extra-legroom seats", example = "false")
    private boolean legRoom;

    @Schema(description = "True for aisle seats", example = "false")
    private boolean aisle;

    @Schema(description = "True for emergency exit row seats", example = "false")
    private boolean exitRow;
}
