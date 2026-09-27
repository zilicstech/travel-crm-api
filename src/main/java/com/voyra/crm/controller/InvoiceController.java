package com.voyra.crm.controller;

import com.voyra.crm.dto.InvoiceSummaryResponse;
import com.voyra.crm.service.InvoiceService;
import com.voyra.crm.util.PageRequestUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The pre-accounts-module client invoice surface - {@code client_invoice}, read-only history
 * now that the Accounts module (see {@code InvoiceDocumentController}, /api/accounts/invoices)
 * is the only place a customer invoice is raised. Existing rows - real historical invoices, and
 * the seeded demo tenant's own ones (written directly by {@code DemoBusinessDataSeedRunner}
 * through {@code InvoiceService}, never through this controller) - stay visible here
 * permanently; nothing migrates them into the newer {@code invoice} table. Reads are
 * Owner/Accountant/Agent (agent scoped to their own bookings). The supplier-invoice endpoints
 * that used to live here moved to {@code SupplierInvoiceController}
 * (/api/accounts/payables/invoices) as part of the accounts-payable rebuild - see
 * ARCHITECTURE-SPINE AD-1.
 */
@Slf4j
@RestController
@RequestMapping("/api/invoices")
@RequiredArgsConstructor
@Tag(name = "Invoices", description = "Read-only history of pre-Accounts-module client invoices")
@PreAuthorize("hasAnyRole('AGENCY_OWNER', 'ACCOUNTANT', 'AGENT')")
public class InvoiceController {

    private final InvoiceService invoiceService;

    @GetMapping("/client")
    @Operation(summary = "List client invoices",
            description = "Agents see only their own; Owners and Accountants see the whole agency. Supply ?page= for a paged "
                    + "envelope; omit it for the full list as a plain array.")
    public ResponseEntity<Object> listClientInvoices(
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size) {
        Pageable pageable = PageRequestUtil.resolve(page, size);
        return ResponseEntity.ok(pageable == null
                ? invoiceService.listClientInvoices()
                : invoiceService.listClientInvoices(pageable));
    }

    @GetMapping("/summary")
    @Operation(summary = "Invoice KPI summary", description = "Collected, pending-to-collect, GST, paid-to-suppliers, pending-to-pay.")
    public ResponseEntity<InvoiceSummaryResponse> getSummary() {
        return ResponseEntity.ok(invoiceService.getSummary());
    }
}
