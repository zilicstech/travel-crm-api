package com.voyra.crm.controller;

import com.voyra.crm.dto.TaxPreviewRequest;
import com.voyra.crm.dto.TaxPreviewResponse;
import com.voyra.crm.models.TaxComputationRequest;
import com.voyra.crm.models.TaxComputationResult;
import com.voyra.crm.service.TaxEngine;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tax invoices and proformas. Only the tax preview lives here so far - draft/issue/cancel and
 * the rest of the invoice lifecycle land with Epic 3 (numbering, currency lock, the invoice
 * and invoice_line_item tables).
 */
@Slf4j
@RestController
@RequestMapping("/api/accounts/invoices")
@RequiredArgsConstructor
@Tag(name = "Accounts - Invoices", description = "Tax invoices and proformas")
@PreAuthorize("hasAnyRole('AGENCY_OWNER', 'ACCOUNTANT', 'AGENT')")
public class InvoiceDocumentController {

    private final TaxEngine taxEngine;

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
