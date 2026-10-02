package com.voyra.crm.dto;

import com.voyra.crm.enums.ServiceType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "One supplier's share of a booking's cost")
public class BookingCostComponentResponse {

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String id;

    @Schema(example = "p-001")
    private String bookingPassengerId;

    @Schema(example = "FLIGHT")
    private ServiceType serviceType;

    @Schema(example = "v-dubai-dmc")
    private String vendorId;

    @Schema(example = "Dubai Desert DMC")
    private String vendorName;

    @Schema(example = "Delhi-Dubai-Delhi airfare")
    private String description;

    @Schema(example = "32000.00")
    private BigDecimal netCost;

    @Schema(example = "INR")
    private String currencyCode;

    @Schema(example = "1.000000")
    private BigDecimal fxRateToInr;

    @Schema(example = "32000.00")
    private BigDecimal netCostInr;

    @Schema(example = "2026-11-10")
    private LocalDate dueDate;

    @Schema(example = "0")
    private Integer sortOrder;
}
