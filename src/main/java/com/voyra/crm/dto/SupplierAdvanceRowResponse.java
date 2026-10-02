package com.voyra.crm.dto;

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
@Schema(description = "One still-active advance payment feeding a vendor's unallocated pool")
public class SupplierAdvanceRowResponse {

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String id;

    @Schema(example = "PV/2026-27/0001")
    private String voucherNumber;

    @Schema(example = "2026-09-18")
    private LocalDate paidOn;

    @Schema(example = "50000.00")
    private BigDecimal amount;

    @Schema(example = "50000.00")
    private BigDecimal amountInr;
}
