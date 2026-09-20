package com.voyra.crm.controller;

import com.voyra.crm.dto.CostReconciliationRowResponse;
import com.voyra.crm.dto.PayablesDashboardSummaryResponse;
import com.voyra.crm.dto.PurchaseRegisterRowResponse;
import com.voyra.crm.service.PayablesDashboardService;
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
import java.util.List;

/** Payables-wide aggregates - Owner/Accountant only, agency-wide by nature (never agent-scoped). */
@RestController
@RequestMapping("/api/accounts/payables/dashboard")
@RequiredArgsConstructor
@Tag(name = "Payables - Dashboard", description = "Payables summary, the Purchase & ITC register, and Cost Reconciliation")
@PreAuthorize("hasAnyRole('AGENCY_OWNER', 'ACCOUNTANT')")
public class PayablesDashboardController {

    private final PayablesDashboardService payablesDashboardService;

    @GetMapping("/summary")
    @Operation(summary = "Dashboard tiles", description = "Payable outstanding, overdue, advances held, input tax this month, bills awaiting approval.")
    public ResponseEntity<PayablesDashboardSummaryResponse> summary() {
        return ResponseEntity.ok(payablesDashboardService.summary());
    }

    @GetMapping("/purchase-register")
    @Operation(summary = "Purchase & ITC register", description = "Grouped by SAC code, rate and ITC eligibility. Defaults to the trailing month when from/to are omitted.")
    public ResponseEntity<List<PurchaseRegisterRowResponse>> purchaseRegister(
            @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate[] range = resolveRange(from, to);
        return ResponseEntity.ok(payablesDashboardService.purchaseRegister(range[0], range[1]));
    }

    @GetMapping("/export/purchase-register")
    @Operation(summary = "Export the Purchase & ITC register as Excel")
    public ResponseEntity<byte[]> exportPurchaseRegister(
            @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate[] range = resolveRange(from, to);
        byte[] xlsx = payablesDashboardService.exportPurchaseRegisterXlsx(range[0], range[1]);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("purchase-register.xlsx", StandardCharsets.UTF_8).build().toString())
                .body(xlsx);
    }

    @GetMapping("/cost-reconciliation")
    @Operation(summary = "Booking estimate vs. actual supplier cost", description = "Read-only - never writes back to booking.netCost/profit (AD-9).")
    public ResponseEntity<List<CostReconciliationRowResponse>> costReconciliation() {
        return ResponseEntity.ok(payablesDashboardService.costReconciliation());
    }

    private static LocalDate[] resolveRange(LocalDate from, LocalDate to) {
        LocalDate resolvedTo = to != null ? to : LocalDate.now();
        LocalDate resolvedFrom = from != null ? from : resolvedTo.minusMonths(1);
        return new LocalDate[]{resolvedFrom, resolvedTo};
    }
}
