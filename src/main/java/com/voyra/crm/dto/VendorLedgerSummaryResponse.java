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
@Schema(description = "One vendor's billed/paid/payable/advance roll-up - the Payables console's Suppliers screen")
public class VendorLedgerSummaryResponse {

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String vendorId;

    @Schema(example = "IndiGo")
    private String vendorName;

    @Schema(example = "500000.00")
    private BigDecimal creditLimitInr;

    @Schema(example = "13020.00")
    private BigDecimal billedInr;

    @Schema(example = "0.00")
    private BigDecimal paidInr;

    @Schema(description = "Positive balance owed to the vendor", example = "13020.00")
    private BigDecimal payableInr;

    @Schema(description = "Positive balance the vendor holds of ours (a deposit)", example = "0.00")
    private BigDecimal advanceInr;
}
