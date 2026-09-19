package com.voyra.crm.controller;

import com.voyra.crm.dto.InvoiceCancelRequest;
import com.voyra.crm.dto.InvoiceDraftRequest;
import com.voyra.crm.dto.InvoiceResponse;
import com.voyra.crm.dto.TaxPreviewRequest;
import com.voyra.crm.dto.TaxPreviewResponse;
import com.voyra.crm.enums.InvoiceLifecycle;
import com.voyra.crm.models.TaxComputationRequest;
import com.voyra.crm.models.TaxComputationResult;
import com.voyra.crm.service.InvoiceDocumentService;
import com.voyra.crm.service.TaxEngine;
import com.voyra.crm.util.PageRequestUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Tax invoices and proformas. Draft CRUD, issue, proforma issue/conversion, and cancel are all
 * live as of this epic. The PDF route lands with Epic 8.
 */
@Slf4j
@RestController
@RequestMapping("/api/accounts/invoices")
@RequiredArgsConstructor
@Tag(name = "Accounts - Invoices", description = "Tax invoices and proformas")
@PreAuthorize("hasAnyRole('AGENCY_OWNER', 'ACCOUNTANT', 'AGENT')")
public class InvoiceDocumentController {

    private final TaxEngine taxEngine;
    private final InvoiceDocumentService invoiceDocumentService;

    @PostMapping
    @PreAuthorize("hasAnyRole('AGENCY_OWNER', 'ACCOUNTANT')")
    @Operation(summary = "Create a draft invoice for a booking")
    public ResponseEntity<InvoiceResponse> create(@Valid @RequestBody InvoiceDraftRequest request) {
        return ResponseEntity.ok(invoiceDocumentService.createDraft(request));
    }

    @GetMapping
    @Operation(summary = "List invoices", description = "Supply ?page= for a paged envelope; omit it for the full list as a plain array. An Agent sees only their own bookings' invoices.")
    public ResponseEntity<Object> list(
            @RequestParam(value = "status", required = false) InvoiceLifecycle status,
            @RequestParam(value = "clientId", required = false) String clientId,
            @RequestParam(value = "bookingId", required = false) String bookingId,
            @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size) {
        Pageable pageable = PageRequestUtil.resolve(page, size);
        return ResponseEntity.ok(invoiceDocumentService.list(status, clientId, bookingId, from, to, pageable));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one invoice with its line items")
    public ResponseEntity<InvoiceResponse> get(@PathVariable String id) {
        return ResponseEntity.ok(invoiceDocumentService.get(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('AGENCY_OWNER', 'ACCOUNTANT')")
    @Operation(summary = "Replace a draft's header and line list", description = "DRAFT only - replaces every line.")
    public ResponseEntity<InvoiceResponse> update(@PathVariable String id, @Valid @RequestBody InvoiceDraftRequest request) {
        return ResponseEntity.ok(invoiceDocumentService.updateDraft(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('AGENCY_OWNER', 'ACCOUNTANT')")
    @Operation(summary = "Delete a draft", description = "DRAFT only - never consumes an invoice number.")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        invoiceDocumentService.deleteDraft(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/issue")
    @PreAuthorize("hasAnyRole('AGENCY_OWNER', 'ACCOUNTANT')")
    @Operation(summary = "Issue a draft as a tax invoice", description = "Allocates the invoice number and locks the FX rate. Irreversible - the invoice becomes read-only.")
    public ResponseEntity<InvoiceResponse> issue(@PathVariable String id) {
        return ResponseEntity.ok(invoiceDocumentService.issue(id));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('AGENCY_OWNER', 'ACCOUNTANT')")
    @Operation(summary = "Cancel an issued invoice or proforma with no receipts against it", description = "A settled tax invoice is corrected with a credit note instead - see Epic 6.")
    public ResponseEntity<InvoiceResponse> cancel(@PathVariable String id, @Valid @RequestBody InvoiceCancelRequest request) {
        return ResponseEntity.ok(invoiceDocumentService.cancel(id, request.getReason()));
    }

    @PostMapping("/{id}/issue-proforma")
    @PreAuthorize("hasAnyRole('AGENCY_OWNER', 'ACCOUNTANT')")
    @Operation(summary = "Issue a draft as a proforma", description = "Allocates a PI number and locks the FX rate. Carries no GST liability; receipts against it are advances.")
    public ResponseEntity<InvoiceResponse> issueProforma(@PathVariable String id) {
        return ResponseEntity.ok(invoiceDocumentService.issueProforma(id));
    }

    @PostMapping("/{id}/convert-to-tax-invoice")
    @PreAuthorize("hasAnyRole('AGENCY_OWNER', 'ACCOUNTANT')")
    @Operation(summary = "Convert an issued proforma into a tax invoice", description = "Creates a new INV-numbered row; the proforma's advance receipts carry over and the proforma itself moves to CANCELLED.")
    public ResponseEntity<InvoiceResponse> convertToTaxInvoice(@PathVariable String id) {
        return ResponseEntity.ok(invoiceDocumentService.convertToTaxInvoice(id));
    }

    @PostMapping("/preview-tax")
    @PreAuthorize("hasAnyRole('AGENCY_OWNER', 'ACCOUNTANT')")
    @Operation(summary = "Preview GST/TCS for a client and amount", description = "Persists nothing - what the invoice builder calls live as the accountant fills the form.")
    public ResponseEntity<TaxPreviewResponse> previewTax(@Valid @RequestBody TaxPreviewRequest request) {
        TaxComputationResult result = taxEngine.compute(new TaxComputationRequest(
                request.getClientId(),
                request.getTaxableAmount(),
                request.getSupplyNature(),
                request.getPlaceOfSupplyCodeOverride(),
                Boolean.TRUE.equals(request.getExportOfServiceRequested()),
                request.getCurrencyCode() != null ? request.getCurrencyCode() : "INR"));

        return ResponseEntity.ok(TaxPreviewResponse.builder()
                .taxTreatment(result.taxTreatment())
                .placeOfSupplyCode(result.placeOfSupplyCode())
                .taxableValue(result.taxableValue())
                .gstRatePercent(result.gstRatePercent())
                .cgstRatePercent(result.cgstRatePercent())
                .sgstRatePercent(result.sgstRatePercent())
                .igstRatePercent(result.igstRatePercent())
                .cgstAmount(result.cgstAmount())
                .sgstAmount(result.sgstAmount())
                .igstAmount(result.igstAmount())
                .gstTotal(result.gstTotal())
                .tcsRatePercent(result.tcsRatePercent())
                .tcsSection(result.tcsSection())
                .tcsBaseAmount(result.tcsBaseAmount())
                .tcsAmount(result.tcsAmount())
                .grandTotal(result.grandTotal())
                .build());
    }
}
