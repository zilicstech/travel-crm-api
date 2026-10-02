package com.voyra.crm.controller;

import com.voyra.crm.dto.BalanceSheetResponse;
import com.voyra.crm.dto.ProfitAndLossResponse;
import com.voyra.crm.dto.TrialBalanceResponse;
import com.voyra.crm.service.FinancialStatementsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

/**
 * Trial Balance, P&amp;L and Balance Sheet - ACCOUNTING_EXPANSION_ARCHITECTURE.md §1.9. Owner/
 * Accountant only, matching every other GL-adjacent controller's gate.
 */
@RestController
@RequestMapping("/api/accounts/financial-statements")
@RequiredArgsConstructor
@Tag(name = "Accounts - Financial Statements", description = "Trial Balance, Profit & Loss and Balance Sheet, built on the general ledger")
@PreAuthorize("hasAnyRole('AGENCY_OWNER', 'ACCOUNTANT')")
public class FinancialStatementsController {

    private static final MediaType XLSX = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final FinancialStatementsService financialStatementsService;

    @GetMapping("/trial-balance")
    @Operation(summary = "Trial Balance", description = "Every account's debit/credit movement over a date range. Defaults to the trailing month.")
    public ResponseEntity<TrialBalanceResponse> trialBalance(
            @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate[] range = resolveRange(from, to);
        return ResponseEntity.ok(financialStatementsService.trialBalance(range[0], range[1]));
    }

    @GetMapping("/profit-and-loss")
    @Operation(summary = "Profit & Loss", description = "Income and expense movement over a date range. Defaults to the trailing month.")
    public ResponseEntity<ProfitAndLossResponse> profitAndLoss(
            @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate[] range = resolveRange(from, to);
        return ResponseEntity.ok(financialStatementsService.profitAndLoss(range[0], range[1]));
    }

    @GetMapping("/balance-sheet")
    @Operation(summary = "Balance Sheet", description = "Cumulative asset/liability/equity balances as of a date. Defaults to today.")
    public ResponseEntity<BalanceSheetResponse> balanceSheet(
            @RequestParam(value = "asOf", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return ResponseEntity.ok(financialStatementsService.balanceSheet(asOf != null ? asOf : LocalDate.now()));
    }

    @GetMapping("/export/trial-balance")
    @Operation(summary = "Export the Trial Balance as Excel")
    public ResponseEntity<byte[]> exportTrialBalance(
            @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate[] range = resolveRange(from, to);
        return xlsx(financialStatementsService.exportTrialBalanceXlsx(range[0], range[1]), "trial-balance.xlsx");
    }

    @GetMapping("/export/profit-and-loss")
    @Operation(summary = "Export the Profit & Loss statement as Excel")
    public ResponseEntity<byte[]> exportProfitAndLoss(
            @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate[] range = resolveRange(from, to);
        return xlsx(financialStatementsService.exportProfitAndLossXlsx(range[0], range[1]), "profit-and-loss.xlsx");
    }

    @GetMapping("/export/balance-sheet")
    @Operation(summary = "Export the Balance Sheet as Excel")
    public ResponseEntity<byte[]> exportBalanceSheet(
            @RequestParam(value = "asOf", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return xlsx(financialStatementsService.exportBalanceSheetXlsx(asOf != null ? asOf : LocalDate.now()), "balance-sheet.xlsx");
    }

    private static ResponseEntity<byte[]> xlsx(byte[] body, String filename) {
        return ResponseEntity.ok()
                .contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build().toString())
                .body(body);
    }

    private static LocalDate[] resolveRange(LocalDate from, LocalDate to) {
        LocalDate resolvedTo = to != null ? to : LocalDate.now();
        LocalDate resolvedFrom = from != null ? from : resolvedTo.minusMonths(1);
        return new LocalDate[]{resolvedFrom, resolvedTo};
    }
}
