package com.voyra.crm.dto;

import com.voyra.crm.enums.LedgerPartyType;
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
@Schema(description = "The Ledger screen: every debit and credit for one customer or supplier, or for all of them, in a date range")
public class PartyLedgerResponse {

    @Schema(example = "CUSTOMER")
    private LedgerPartyType partyType;

    @Schema(description = "Set when one party is shown", example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String partyId;

    @Schema(example = "Sharma Family")
    private String partyName;

    @Schema(example = "2026-04-01")
    private LocalDate from;

    @Schema(example = "2027-03-31")
    private LocalDate to;

    @Schema(description = "Balance before the range - one party only", example = "0.00")
    private BigDecimal openingBalanceInr;

    @Schema(description = "Balance at the end of the range - one party only", example = "12000.00")
    private BigDecimal closingBalanceInr;

    @Schema(example = "54000.00")
    private BigDecimal totalDebitInr;

    @Schema(example = "42000.00")
    private BigDecimal totalCreditInr;

    @Schema(description = "Oldest first")
    private List<PartyLedgerEntryResponse> entries;
}
