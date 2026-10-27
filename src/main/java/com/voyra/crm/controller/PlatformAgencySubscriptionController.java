package com.voyra.crm.controller;

import com.voyra.crm.dto.SubscriptionAdminResponse;
import com.voyra.crm.dto.SubscriptionInvoicePaidRequest;
import com.voyra.crm.dto.SubscriptionInvoiceResponse;
import com.voyra.crm.dto.SubscriptionInvoiceVoidRequest;
import com.voyra.crm.dto.SubscriptionUpdateRequest;
import com.voyra.crm.dto.SubscriptionWaiveRequest;
import com.voyra.crm.dto.TrialExtendRequest;
import com.voyra.crm.service.SubscriptionBillingService;
import com.voyra.crm.service.SubscriptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/platform/agencies/{tenantId}/subscription")
@RequiredArgsConstructor
@Tag(name = "Platform - Agency subscription", description = "SUPER_ADMIN plan, SKU, seat, waiver and trial changes; subscription invoices")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class PlatformAgencySubscriptionController {

    private final SubscriptionService service;
    private final SubscriptionBillingService billingService;

    @GetMapping
    @Operation(summary = "An agency's subscription, seat usage and waiver reason")
    public ResponseEntity<SubscriptionAdminResponse> get(@PathVariable String tenantId) {
        return ResponseEntity.ok(service.adminView(tenantId));
    }

    @PutMapping
    @Operation(summary = "Change plan, SKUs, extra seats and billing cycle")
    public ResponseEntity<SubscriptionAdminResponse> update(@PathVariable String tenantId,
                                                            @Valid @RequestBody SubscriptionUpdateRequest request) {
        return ResponseEntity.ok(service.update(tenantId, request));
    }

    @PostMapping("/waive")
    @Operation(summary = "Waive the fee until a date, or without expiry")
    public ResponseEntity<SubscriptionAdminResponse> waive(@PathVariable String tenantId,
                                                           @Valid @RequestBody SubscriptionWaiveRequest request) {
        return ResponseEntity.ok(service.waive(tenantId, request));
    }

    @PostMapping("/extend-trial")
    @Operation(summary = "Extend or restart the Suite trial")
    public ResponseEntity<SubscriptionAdminResponse> extendTrial(@PathVariable String tenantId,
                                                                 @Valid @RequestBody TrialExtendRequest request) {
        return ResponseEntity.ok(service.extendTrial(tenantId, request));
    }

    @GetMapping("/invoices")
    @Operation(summary = "The agency's subscription invoices, newest first")
    public ResponseEntity<List<SubscriptionInvoiceResponse>> invoices(@PathVariable String tenantId) {
        return ResponseEntity.ok(billingService.listForPlatform(tenantId));
    }

    @PostMapping("/invoices/raise")
    @Operation(summary = "Raise the next period's invoice now",
            description = "409 for a founding agency (waived without expiry), a Starter plan, or when the period already has an open or paid invoice.")
    public ResponseEntity<SubscriptionInvoiceResponse> raise(@PathVariable String tenantId) {
        return ResponseEntity.status(HttpStatus.CREATED).body(billingService.raiseNow(tenantId));
    }

    @PostMapping("/invoices/{id}/mark-paid")
    @Operation(summary = "Mark an invoice paid by bank transfer",
            description = "Moves the agency onto the paid period (ACTIVE, waiver cleared). Audited.")
    public ResponseEntity<SubscriptionInvoiceResponse> markPaid(@PathVariable String tenantId, @PathVariable String id,
                                                                @Valid @RequestBody SubscriptionInvoicePaidRequest request) {
        return ResponseEntity.ok(billingService.markPaidByBank(tenantId, id, request.getReference()));
    }

    @PostMapping("/invoices/{id}/void")
    @Operation(summary = "Void an unpaid invoice", description = "Audited. A paid invoice cannot be voided.")
    public ResponseEntity<SubscriptionInvoiceResponse> voidInvoice(@PathVariable String tenantId, @PathVariable String id,
                                                                   @Valid @RequestBody SubscriptionInvoiceVoidRequest request) {
        return ResponseEntity.ok(billingService.voidInvoice(tenantId, id, request.getReason()));
    }

    @GetMapping("/invoices/{id}/pdf")
    @Operation(summary = "Download an invoice PDF")
    public ResponseEntity<Resource> pdf(@PathVariable String tenantId, @PathVariable String id) {
        SubscriptionBillingService.InvoicePdf pdf = billingService.pdf(tenantId, id);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(pdf.fileName(), StandardCharsets.UTF_8).build().toString())
                .body(pdf.resource());
    }
}
