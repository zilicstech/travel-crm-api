package com.voyra.crm.dto;

import com.voyra.crm.enums.ServiceType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Schema(description = "One supplier's share of a booking's cost - a DMC, an airline, a hotel. A component naming no vendor raises no supplier bill.")
public class BookingCostComponentRequest {

    @Schema(example = "p-001")
    private String bookingPassengerId;

    @NotNull(message = "Service type is required")
    @Schema(example = "FLIGHT")
    private ServiceType serviceType;

    @Schema(example = "v-dubai-dmc")
    private String vendorId;

    @Schema(example = "Delhi-Dubai-Delhi airfare")
    private String description;

    @NotNull(message = "Net cost is required")
    @Schema(example = "32000.00")
    private BigDecimal netCost;

    @Schema(example = "INR")
    private String currencyCode;

    @Schema(example = "1.000000")
    private BigDecimal fxRateToInr;

    @Schema(example = "2026-11-10")
    private LocalDate dueDate;
}
