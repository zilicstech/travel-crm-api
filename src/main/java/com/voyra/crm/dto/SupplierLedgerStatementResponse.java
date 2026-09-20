package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A vendor's statement of account over an optional date range")
public class SupplierLedgerStatementResponse {

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String vendorId;

    @Schema(example = "IndiGo")
    private String vendorName;

    @Schema(example = "2026-04-01")
    private LocalDate from;

    @Schema(example = "2026-09-30")
    private LocalDate to;

    @Schema(example = "0.00")
    private BigDecimal openingBalanceInr;

    @Schema(example = "13020.00")
    private BigDecimal closingBalanceInr;

    @Schema(description = "Every entry in range, oldest first")
    private List<SupplierLedgerEntryResponse> entries;
}
