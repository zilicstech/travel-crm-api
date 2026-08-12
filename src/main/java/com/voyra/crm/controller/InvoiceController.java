package com.voyra.crm.controller;

import com.voyra.crm.dto.ClientInvoiceCreateRequest;
import com.voyra.crm.dto.ClientInvoicePaymentRequest;
import com.voyra.crm.dto.ClientInvoiceResponse;
import com.voyra.crm.dto.InvoiceSummaryResponse;
import com.voyra.crm.dto.SupplierInvoiceCreateRequest;
import com.voyra.crm.dto.SupplierInvoiceResponse;
import com.voyra.crm.dto.SupplierInvoiceStatusUpdateRequest;
import com.voyra.crm.service.InvoiceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Shared between AGENCY_OWNER and AGENT - same rules for both, scope resolved per caller. */
@Slf4j
@RestController
@RequestMapping("/api/invoices")
@RequiredArgsConstructor
@Tag(name = "Invoices", description = "Client (money to collect) and Supplier (money to pay) invoices")
@PreAuthorize("hasAnyRole('AGENCY_OWNER', 'AGENT')")
public class InvoiceController {

    private final InvoiceService invoiceService;

    @PostMapping("/client")
    @Operation(summary = "Generate a client invoice", description = "GST is always server-computed from gstRate (default 18%).")
    public ResponseEntity<ClientInvoiceResponse> createClientInvoice(@Valid @RequestBody ClientInvoiceCreateRequest request) {
        return ResponseEntity.ok(invoiceService.createClientInvoice(request));
    }

    @GetMapping("/client")
    @Operation(summary = "List client invoices", description = "Agents see only their own; Owners see the whole agency.")
    public ResponseEntity<List<ClientInvoiceResponse>> listClientInvoices() {
        return ResponseEntity.ok(invoiceService.listClientInvoices());
    }

    @PatchMapping("/client/{id}/payment")
    @Operation(summary = "Record a payment against a client invoice", description = "Status (Paid/Partial/Pending) is always server-derived from amountPaid.")
    public ResponseEntity<ClientInvoiceResponse> recordPayment(@PathVariable String id,
                                                                @Valid @RequestBody ClientInvoicePaymentRequest request) {
        return ResponseEntity.ok(invoiceService.recordPayment(id, request));
    }

    @PostMapping("/supplier")
    @Operation(summary = "Record a supplier invoice")
    public ResponseEntity<SupplierInvoiceResponse> createSupplierInvoice(@Valid @RequestBody SupplierInvoiceCreateRequest request) {
        return ResponseEntity.ok(invoiceService.createSupplierInvoice(request));
    }

    @GetMapping("/supplier")
    @Operation(summary = "List supplier invoices", description = "Agency-wide for both roles - supplier payables aren't agent-attributed.")
    public ResponseEntity<List<SupplierInvoiceResponse>> listSupplierInvoices() {
        return ResponseEntity.ok(invoiceService.listSupplierInvoices());
    }

    @PatchMapping("/supplier/{id}/status")
    @Operation(summary = "Update a supplier invoice's payment status")
    public ResponseEntity<SupplierInvoiceResponse> updateSupplierInvoiceStatus(
            @PathVariable String id, @Valid @RequestBody SupplierInvoiceStatusUpdateRequest request) {
        return ResponseEntity.ok(invoiceService.updateSupplierInvoiceStatus(id, request));
    }

    @GetMapping("/summary")
    @Operation(summary = "Invoice KPI summary", description = "Collected, pending-to-collect, GST, paid-to-suppliers, pending-to-pay.")
    public ResponseEntity<InvoiceSummaryResponse> getSummary() {
        return ResponseEntity.ok(invoiceService.getSummary());
    }
}
