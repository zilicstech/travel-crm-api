package com.voyra.crm.controller;

import com.voyra.crm.dto.AccountsDashboardSummaryResponse;
import com.voyra.crm.dto.GstSummaryRowResponse;
import com.voyra.crm.dto.TcsSummaryRowResponse;
import com.voyra.crm.service.AccountsDashboardService;
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

/** Accounts-wide aggregates - Owner/Accountant only, agency-wide by nature (never agent-scoped). */
@RestController
@RequestMapping("/api/accounts/dashboard")
@RequiredArgsConstructor
@Tag(name = "Accounts - Dashboard", description = "Billing/collections summary and the GST/TCS registers")
@PreAuthorize("hasAnyRole('AGENCY_OWNER', 'ACCOUNTANT')")
public class AccountsDashboardController {

    private final AccountsDashboardService accountsDashboardService;

    @GetMapping("/summary")
    @Operation(summary = "Dashboard tiles", description = "Billed/collected this month, outstanding, overdue, advance held, output tax this month.")
    public ResponseEntity<AccountsDashboardSummaryResponse> summary() {
        return ResponseEntity.ok(accountsDashboardService.summary());
    }

    @GetMapping("/gst-summary")
    @Operation(summary = "GST register", description = "Grouped by SAC code and rate. Defaults to the trailing month when from/to are omitted.")
    public ResponseEntity<List<GstSummaryRowResponse>> gstSummary(
            @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate[] range = resolveRange(from, to);
        return ResponseEntity.ok(accountsDashboardService.gstSummary(range[0], range[1]));
    }

    @GetMapping("/tcs-summary")
    @Operation(summary = "TCS register", description = "Grouped by section 206C(1G) etc. Defaults to the trailing month when from/to are omitted.")
    public ResponseEntity<List<TcsSummaryRowResponse>> tcsSummary(
            @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate[] range = resolveRange(from, to);
        return ResponseEntity.ok(accountsDashboardService.tcsSummary(range[0], range[1]));
    }

    @GetMapping("/export/gst")
    @Operation(summary = "Export the GST register as Excel")
    public ResponseEntity<byte[]> exportGst(
            @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate[] range = resolveRange(from, to);
        byte[] xlsx = accountsDashboardService.exportGstXlsx(range[0], range[1]);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("gst-register.xlsx", StandardCharsets.UTF_8).build().toString())
                .body(xlsx);
    }

    private static LocalDate[] resolveRange(LocalDate from, LocalDate to) {
        LocalDate resolvedTo = to != null ? to : LocalDate.now();
        LocalDate resolvedFrom = from != null ? from : resolvedTo.minusMonths(1);
        return new LocalDate[]{resolvedFrom, resolvedTo};
    }
}
