package com.voyra.crm.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Monthly INR prices (excl. GST) of the paid plans and one extra staff seat")
public class PlanPricesRequest {

    @JsonProperty("LITE")
    @NotNull(message = "LITE is required")
    @DecimalMin(value = "0", inclusive = false, message = "LITE must be more than 0")
    @DecimalMax(value = "1000000", message = "LITE must be at most 1000000")
    @Digits(integer = 7, fraction = 2, message = "LITE can have at most two decimals")
    @Schema(example = "2999")
    private BigDecimal lite;

    @JsonProperty("SUITE")
    @NotNull(message = "SUITE is required")
    @DecimalMin(value = "0", inclusive = false, message = "SUITE must be more than 0")
    @DecimalMax(value = "1000000", message = "SUITE must be at most 1000000")
    @Digits(integer = 7, fraction = 2, message = "SUITE can have at most two decimals")
    @Schema(example = "4999")
    private BigDecimal suite;

    @JsonProperty("STAFF_SEATS")
    @NotNull(message = "STAFF_SEATS is required")
    @DecimalMin(value = "0", inclusive = false, message = "STAFF_SEATS must be more than 0")
    @DecimalMax(value = "1000000", message = "STAFF_SEATS must be at most 1000000")
    @Digits(integer = 7, fraction = 2, message = "STAFF_SEATS can have at most two decimals")
    @Schema(example = "399")
    private BigDecimal staffSeats;
}
