package com.voyra.crm.controller;

import com.voyra.crm.dto.PartyLedgerResponse;
import com.voyra.crm.enums.LedgerPartyType;
import com.voyra.crm.service.PartyLedgerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** The Ledger screen - customer and supplier ledgers behind one filter. Owner/Accountant only. */
@RestController
@RequestMapping("/api/accounts/ledger")
@RequiredArgsConstructor
@Tag(name = "Accounts - Ledger", description = "Customer statements, AR ageing, opening balances")
@PreAuthorize("hasAnyRole('AGENCY_OWNER', 'ACCOUNTANT')")
public class PartyLedgerController {

    private final PartyLedgerService partyLedgerService;

    @GetMapping("/entries")
    @Operation(summary = "Ledger entries", description = "Every debit and credit for customers, suppliers or both in a date range. "
            + "With a partyId (and partyType CUSTOMER or SUPPLIER) it is that party's statement with opening, running and closing balances.")
    public ResponseEntity<PartyLedgerResponse> entries(
            @RequestParam(value = "partyType", required = false) LedgerPartyType partyType,
            @RequestParam(value = "partyId", required = false) String partyId,
            @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(partyLedgerService.entries(partyType, partyId, from, to));
    }
}
