package com.voyra.crm.controller;

import com.voyra.crm.dto.AccountLedgerResponse;
import com.voyra.crm.dto.JournalEntryResponse;
import com.voyra.crm.dto.LedgerAccountBalanceResponse;
import com.voyra.crm.enums.JournalSourceType;
import com.voyra.crm.service.GeneralLedgerQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * Read-only general-ledger views behind the Chart of Accounts, General Ledger and Journals
 * screens. Owner/Accountant only, matching every other GL-adjacent controller's gate.
 */
@RestController
@RequestMapping("/api/accounts/gl")
@RequiredArgsConstructor
@Tag(name = "Accounts - General Ledger", description = "Chart of accounts with balances, per-account ledger, journal register")
@PreAuthorize("hasAnyRole('AGENCY_OWNER', 'ACCOUNTANT')")
public class GeneralLedgerController {

    private final GeneralLedgerQueryService generalLedgerQueryService;

    @GetMapping("/accounts")
    @Operation(summary = "Chart of accounts with each account's cumulative balance as of a date", description = "Defaults to today.")
    public ResponseEntity<List<LedgerAccountBalanceResponse>> chartOfAccounts(
            @RequestParam(value = "asOf", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return ResponseEntity.ok(generalLedgerQueryService.chartOfAccounts(asOf != null ? asOf : LocalDate.now()));
    }

    @GetMapping("/accounts/{code}/ledger")
    @Operation(summary = "One account's General Ledger", description = "Opening balance, every movement with a running balance, closing balance. Defaults to the trailing month.")
    public ResponseEntity<AccountLedgerResponse> accountLedger(
            @PathVariable String code,
            @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate[] range = resolveRange(from, to);
        return ResponseEntity.ok(generalLedgerQueryService.accountLedger(code, range[0], range[1]));
    }

    @GetMapping("/journals")
    @Operation(summary = "Journal register", description = "Every journal entry in a date range with its lines, newest first. Defaults to the trailing month.")
    public ResponseEntity<List<JournalEntryResponse>> journalRegister(
            @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(value = "sourceType", required = false) JournalSourceType sourceType) {
        LocalDate[] range = resolveRange(from, to);
        return ResponseEntity.ok(generalLedgerQueryService.journalRegister(range[0], range[1], sourceType));
    }

    private static LocalDate[] resolveRange(LocalDate from, LocalDate to) {
        LocalDate resolvedTo = to != null ? to : LocalDate.now();
        LocalDate resolvedFrom = from != null ? from : resolvedTo.minusMonths(1);
        return new LocalDate[]{resolvedFrom, resolvedTo};
    }
}
