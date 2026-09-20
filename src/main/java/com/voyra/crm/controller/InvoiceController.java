package com.voyra.crm.controller;

import com.voyra.crm.dto.ClientInvoiceCreateRequest;
import com.voyra.crm.dto.ClientInvoicePaymentRequest;
import com.voyra.crm.dto.ClientInvoiceResponse;
import com.voyra.crm.dto.InvoiceSummaryResponse;
import com.voyra.crm.service.InvoiceService;
import com.voyra.crm.util.PageRequestUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The pre-accounts-module client invoice surface - {@code client_invoice}, kept on its original
 * contract (see architecture note on why a new {@code invoice} table was added instead of
 * migrating this one). Reads are Owner/Accountant/Agent (agent scoped to their own bookings);
 * writes are Owner/Accountant only. The supplier-invoice endpoints that used to live here moved
 * to {@code SupplierInvoiceController} (/api/accounts/payables/invoices) as part of the
 * accounts-payable rebuild - see ARCHITECTURE-SPINE AD-1.
 */
@Slf4j
@RestController
@RequestMapping("/api/invoices")
@RequiredArgsConstructor
@Tag(name = "Invoices", description = "Client (money to collect) and Supplier (money to pay) invoices")
@PreAuthorize("hasAnyRole('AGENCY_OWNER', 'ACCOUNTANT', 'AGENT')")
public class InvoiceController {

    private final InvoiceService invoiceService;

    @PostMapping("/client")
    @PreAuthorize("hasAnyRole('AGENCY_OWNER', 'ACCOUNTANT')")
    @Operation(summary = "Generate a client invoice", description = "GST is always server-computed from gstRate (default 18%).")
    public ResponseEntity<ClientInvoiceResponse> createClientInvoice(@Valid @RequestBody ClientInvoiceCreateRequest request) {
        return ResponseEntity.ok(invoiceService.createClientInvoice(request));
    }

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

    @PatchMapping("/client/{id}/payment")
    @PreAuthorize("hasAnyRole('AGENCY_OWNER', 'ACCOUNTANT')")
    @Operation(summary = "Record a payment against a client invoice", description = "Status (Paid/Partial/Pending) is always server-derived from amountPaid.")
    public ResponseEntity<ClientInvoiceResponse> recordPayment(@PathVariable String id,
                                                                @Valid @RequestBody ClientInvoicePaymentRequest request) {
        return ResponseEntity.ok(invoiceService.recordPayment(id, request));
    }

    @GetMapping("/summary")
    @Operation(summary = "Invoice KPI summary", description = "Collected, pending-to-collect, GST, paid-to-suppliers, pending-to-pay.")
    public ResponseEntity<InvoiceSummaryResponse> getSummary() {
        return ResponseEntity.ok(invoiceService.getSummary());
    }
}
