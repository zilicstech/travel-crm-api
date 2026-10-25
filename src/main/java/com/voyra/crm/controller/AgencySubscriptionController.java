package com.voyra.crm.controller;

import com.voyra.crm.dto.AgencySubscriptionResponse;
import com.voyra.crm.dto.SubscriptionInvoiceResponse;
import com.voyra.crm.dto.SubscriptionPaymentResponse;
import com.voyra.crm.security.SecurityContextUtil;
import com.voyra.crm.service.SubscriptionBillingService;
import com.voyra.crm.service.SubscriptionPaymentService;
import com.voyra.crm.service.SubscriptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/agency/subscription")
@RequiredArgsConstructor
@Tag(name = "Owner - Subscription", description = "The agency's plan, seats, period and invoices; paying them online")
@PreAuthorize("hasRole('AGENCY_OWNER')")
public class AgencySubscriptionController {

    private final SubscriptionService service;
    private final SubscriptionPaymentService paymentService;
    private final SubscriptionBillingService billingService;

    @GetMapping
    @Operation(summary = "Plan, SKUs, seats used/total, status, period and invoices")
    public ResponseEntity<AgencySubscriptionResponse> get() {
        return ResponseEntity.ok(service.agencyView());
    }

    @PostMapping("/invoices/{id}/pay")
    @Operation(summary = "Start paying an invoice online",
            description = "Creates (or reuses) a gateway order for the invoice total; the browser opens checkout with it. "
                    + "The invoice is marked paid only by the signed webhook. Works while read-only.")
    public ResponseEntity<SubscriptionPaymentResponse> pay(@PathVariable String id) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(paymentService.pay(SecurityContextUtil.getCurrentUserOrThrow().tenantId(), id));
    }

    @PostMapping("/invoices/{id}/simulate")
    @Operation(summary = "Simulate the gateway paying an invoice (stub provider and test wallet only)",
            description = "404 unless app.payments.provider=stub. Runs a payment.captured event through the real webhook handler.")
    public ResponseEntity<SubscriptionInvoiceResponse> simulate(@PathVariable String id) {
        return ResponseEntity.ok(paymentService.simulate(SecurityContextUtil.getCurrentUserOrThrow().tenantId(), id));
    }

    @GetMapping("/invoices/{id}/pdf")
    @Operation(summary = "Download an invoice PDF")
    public ResponseEntity<Resource> pdf(@PathVariable String id) {
        SubscriptionBillingService.InvoicePdf pdf = billingService.pdf(SecurityContextUtil.getCurrentUserOrThrow().tenantId(), id);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(pdf.fileName(), StandardCharsets.UTF_8).build().toString())
                .body(pdf.resource());
    }
}
