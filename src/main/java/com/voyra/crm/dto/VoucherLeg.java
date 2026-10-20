package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/** One flight of an air ticket voucher; a connecting journey on one PNR has several, in order. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "One flight leg of an air ticket voucher")
public class VoucherLeg {

    @Schema(description = "Departure airport code", example = "DLA")
    private String from;

    @Schema(description = "Arrival airport code", example = "ADD")
    private String to;

    @Schema(example = "Ethiopian Airlines")
    private String airline;

    @Schema(example = "ET 962")
    private String flightNumber;

    @Schema(example = "Economy")
    private String cabin;

    @Schema(example = "2026-10-31")
    private LocalDate travelDate;

    @Schema(example = "13:45")
    private String departTime;

    @Schema(example = "20:10")
    private String arriveTime;
}
