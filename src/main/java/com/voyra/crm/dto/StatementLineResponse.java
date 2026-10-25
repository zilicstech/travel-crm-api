package com.voyra.crm.dto;

import com.voyra.crm.enums.StatementLineStatus;
import com.voyra.crm.enums.StatementLineType;
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
@Schema(description = "One statement line and what the import did with it")
public class StatementLineResponse {

    @Schema(description = "Line number in the file (the header is line 1)", example = "2")
    private int lineNo;

    @Schema(description = "Supplier booking id from the statement", example = "TJS1234567890")
    private String supplierBookingId;

    @Schema(description = "PNR from the statement", example = "ABC123")
    private String pnr;

    @Schema(description = "SALE or REFUND", example = "SALE")
    private StatementLineType type;

    @Schema(description = "Fare amount on the statement", example = "10000.00")
    private BigDecimal amount;

    @Schema(description = "Commission Voyra earned (SALE) or that was clawed back (REFUND)", example = "180.00")
    private BigDecimal commission;

    @Schema(description = "Statement date", example = "2026-10-05")
    private LocalDate date;

    @Schema(description = "APPLIED, DUPLICATE (already applied once) or UNMATCHED", example = "APPLIED")
    private StatementLineStatus status;

    @Schema(description = "Why the line was not applied, or a note on an applied line", example = "No booking with this booking id or PNR")
    private String reason;

    @Schema(description = "Platform booking the line matched", example = "b1d2e3f4-0000-0000-0000-000000000001")
    private String platformBookingId;
}
