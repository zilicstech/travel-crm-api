package com.voyra.crm.controller;

import com.voyra.crm.dto.SupplierAdvanceApplyRequest;
import com.voyra.crm.dto.SupplierPaymentRequest;
import com.voyra.crm.dto.SupplierPaymentResponse;
import com.voyra.crm.dto.SupplierPaymentReverseRequest;
import com.voyra.crm.service.SupplierPaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Payments to vendors - regular bill payments, pure advances/deposits, and applying an advance to a bill. */
@Slf4j
@RestController
@RequestMapping("/api/accounts/payables/payments")
@RequiredArgsConstructor
@Tag(name = "Payables - Payments", description = "Money paid to vendors")
@PreAuthorize("hasAnyRole('AGENCY_OWNER', 'ACCOUNTANT')")
public class SupplierPaymentController {

    private final SupplierPaymentService supplierPaymentService;

    @PostMapping
    @Operation(summary = "Record a payment", description = "Omit supplierInvoiceId for a pure advance/deposit top-up.")
    public ResponseEntity<SupplierPaymentResponse> pay(@Valid @RequestBody SupplierPaymentRequest request) {
        return ResponseEntity.ok(supplierPaymentService.pay(request));
    }

    @PostMapping("/invoices/{supplierInvoiceId}/apply-advance")
    @Operation(summary = "Apply part or all of an existing advance to a bill", description = "Moves only the bill's balance - posts no ledger row, see AD-5.")
    public ResponseEntity<SupplierPaymentResponse> applyAdvance(@PathVariable String supplierInvoiceId,
                                                                  @Valid @RequestBody SupplierAdvanceApplyRequest request) {
        return ResponseEntity.ok(supplierPaymentService.applyAdvance(supplierInvoiceId, request));
    }

    @PostMapping("/{id}/reverse")
    @Operation(summary = "Reverse a payment", description = "Writes a new, opposite-signed payment - the original is never edited or deleted.")
    public ResponseEntity<SupplierPaymentResponse> reverse(@PathVariable String id, @Valid @RequestBody SupplierPaymentReverseRequest request) {
        return ResponseEntity.ok(supplierPaymentService.reverse(id, request.getReason()));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one payment")
    public ResponseEntity<SupplierPaymentResponse> get(@PathVariable String id) {
        return ResponseEntity.ok(supplierPaymentService.get(id));
    }

    @GetMapping
    @Operation(summary = "List payments", description = "Filter by vendorId or supplierInvoiceId; supply exactly one.")
    public ResponseEntity<List<SupplierPaymentResponse>> list(
            @RequestParam(value = "vendorId", required = false) String vendorId,
            @RequestParam(value = "supplierInvoiceId", required = false) String supplierInvoiceId) {
        if (supplierInvoiceId != null) {
            return ResponseEntity.ok(supplierPaymentService.listForInvoice(supplierInvoiceId));
        }
        if (vendorId != null) {
            return ResponseEntity.ok(supplierPaymentService.listForVendor(vendorId));
        }
        throw new IllegalArgumentException("Supply vendorId or supplierInvoiceId");
    }
}
