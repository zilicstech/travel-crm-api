package com.voyra.crm.dto;

import com.voyra.crm.enums.LedgerPartyType;
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
@Schema(description = "One debit or credit row of a customer's or supplier's ledger, all INR")
public class PartyLedgerEntryResponse {

    @Schema(example = "CUSTOMER")
    private LedgerPartyType partyType;

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String partyId;

    @Schema(example = "Sharma Family")
    private String partyName;

    @Schema(example = "2026-10-02")
    private LocalDate entryDate;

    @Schema(description = "What happened, e.g. INVOICE_RAISED, PAYMENT_RECEIVED, BILL_BOOKED", example = "INVOICE_RAISED")
    private String entryType;

    @Schema(example = "HTL/2026-27/0052")
    private String documentNumber;

    @Schema(example = "Tax invoice HTL/2026-27/0052 raised")
    private String narration;

    @Schema(example = "8f2a1c3d-58cc-4372-a567-0e02b2c3d479")
    private String bookingId;

    @Schema(example = "BK-00164")
    private String bookingRef;

    @Schema(example = "12000.00")
    private BigDecimal debitInr;

    @Schema(example = "0.00")
    private BigDecimal creditInr;

    @Schema(description = "Balance after this row - only when one party is shown (customer: receivable, supplier: payable)", example = "12000.00")
    private BigDecimal runningBalanceInr;
}
