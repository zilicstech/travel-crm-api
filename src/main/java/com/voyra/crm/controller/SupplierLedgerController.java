package com.voyra.crm.controller;

import com.voyra.crm.dto.ApAgeingRowResponse;
import com.voyra.crm.dto.SupplierLedgerStatementResponse;
import com.voyra.crm.dto.SupplierOpeningBalanceRequest;
import com.voyra.crm.dto.VendorLedgerSummaryResponse;
import com.voyra.crm.service.SupplierLedgerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

/** The vendor subsidiary payables ledger - Owner/Accountant only. */
@Slf4j
@RestController
@RequestMapping("/api/accounts/payables/ledger")
@RequiredArgsConstructor
@Tag(name = "Payables - Ledger", description = "Vendor statements, AP ageing, opening balances")
@PreAuthorize("hasAnyRole('AGENCY_OWNER', 'ACCOUNTANT')")
public class SupplierLedgerController {

    private final SupplierLedgerService supplierLedgerService;

    @GetMapping("/vendors/{vendorId}")
    @Operation(summary = "Statement of account", description = "Every debit/credit row with a running INR balance. Omit from/to for the full history.")
    public ResponseEntity<SupplierLedgerStatementResponse> statement(
            @PathVariable String vendorId,
            @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(supplierLedgerService.statement(vendorId, from, to));
    }

    @GetMapping("/vendors/{vendorId}/summary")
    @Operation(summary = "Billed/paid/payable/advance roll-up for one vendor")
    public ResponseEntity<VendorLedgerSummaryResponse> summary(@PathVariable String vendorId) {
        return ResponseEntity.ok(supplierLedgerService.summary(vendorId));
    }

    @GetMapping("/vendors")
    @Operation(summary = "Billed/paid/payable/advance roll-up for every vendor")
    public ResponseEntity<List<VendorLedgerSummaryResponse>> summaryForAllVendors() {
        return ResponseEntity.ok(supplierLedgerService.summaryForAllVendors());
    }

    @GetMapping("/outstanding")
    @Operation(summary = "AP ageing", description = "Every vendor with an unpaid approved bill, bucketed 0-30/31-60/61-90/90+ days past due.")
    public ResponseEntity<List<ApAgeingRowResponse>> outstanding() {
        return ResponseEntity.ok(supplierLedgerService.outstanding());
    }

    @PostMapping("/vendors/{vendorId}/opening-balance")
    @Operation(summary = "Post an opening balance", description = "Onboards a vendor with a pre-existing balance. Positive = we owe the vendor.")
    public ResponseEntity<SupplierLedgerStatementResponse> openingBalance(
            @PathVariable String vendorId, @Valid @RequestBody SupplierOpeningBalanceRequest request) {
        return ResponseEntity.ok(supplierLedgerService.openingBalance(vendorId, request));
    }

    @GetMapping("/vendors/{vendorId}/export/statement.xlsx")
    @Operation(summary = "Export the statement of account as Excel")
    public ResponseEntity<byte[]> exportStatementXlsx(
            @PathVariable String vendorId,
            @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return xlsxResponse(supplierLedgerService.exportStatementXlsx(vendorId, from, to), "vendor-statement.xlsx");
    }

    @GetMapping("/vendors/{vendorId}/export/statement.pdf")
    @Operation(summary = "Export the statement of account as PDF")
    public ResponseEntity<byte[]> exportStatementPdf(
            @PathVariable String vendorId,
            @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return pdfResponse(supplierLedgerService.exportStatementPdf(vendorId, from, to), "vendor-statement.pdf");
    }

    private ResponseEntity<byte[]> xlsxResponse(byte[] xlsx, String filename) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build().toString())
                .body(xlsx);
    }

    private ResponseEntity<byte[]> pdfResponse(byte[] pdf, String filename) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build().toString())
                .body(pdf);
    }
}
