package com.voyra.crm.dto;

import com.voyra.crm.enums.SupplierLedgerEntryType;
import com.voyra.crm.enums.SupplierLedgerSourceType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "One row of a vendor's statement of account, with the running INR balance as of this row. "
        + "Positive running balance = we owe the vendor; negative = the vendor holds our deposit.")
public class SupplierLedgerEntryResponse {

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String id;

    @Schema(example = "2026-09-19")
    private LocalDate entryDate;

    @Schema(example = "BILL_BOOKED")
    private SupplierLedgerEntryType entryType;

    @Schema(example = "SUPPLIER_INVOICE")
    private SupplierLedgerSourceType sourceType;

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String sourceId;

    @Schema(example = "IND/2026/48213")
    private String documentNumber;

    @Schema(example = "Supplier bill IND/2026/48213 booked")
    private String narration;

    @Schema(example = "8f2a1c3d-58cc-4372-a567-0e02b2c3d479")
    private String bookingId;

    @Schema(example = "INR")
    private String currencyCode;

    @Schema(example = "1.000000")
    private BigDecimal fxRateToInr;

    @Schema(example = "0.00")
    private BigDecimal debitAmount;

    @Schema(example = "13020.00")
    private BigDecimal creditAmount;

    @Schema(example = "0.00")
    private BigDecimal debitAmountInr;

    @Schema(example = "13020.00")
    private BigDecimal creditAmountInr;

    @Schema(description = "SUM(credit_inr - debit_inr) up to and including this row, for this vendor", example = "13020.00")
    private BigDecimal runningBalanceInr;

    @Schema(example = "2026-09-19T10:15:00")
    private LocalDateTime createdAt;
}
