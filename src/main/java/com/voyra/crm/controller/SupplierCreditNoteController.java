package com.voyra.crm.controller;

import com.voyra.crm.dto.SupplierCreditNoteCancelRequest;
import com.voyra.crm.dto.SupplierCreditNoteRefundRequest;
import com.voyra.crm.dto.SupplierCreditNoteRequest;
import com.voyra.crm.dto.SupplierCreditNoteResponse;
import com.voyra.crm.service.SupplierCreditNoteService;
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

/** Credit notes a SUPPLIER issued to us - cancellation refunds, rate corrections. Owner/Accountant only. */
@Slf4j
@RestController
@RequestMapping("/api/accounts/payables/credit-notes")
@RequiredArgsConstructor
@Tag(name = "Payables - Credit Notes", description = "Credit notes received from suppliers")
@PreAuthorize("hasAnyRole('AGENCY_OWNER', 'ACCOUNTANT')")
public class SupplierCreditNoteController {

    private final SupplierCreditNoteService supplierCreditNoteService;

    @PostMapping
    @Operation(summary = "Record a supplier credit note")
    public ResponseEntity<SupplierCreditNoteResponse> record(@Valid @RequestBody SupplierCreditNoteRequest request) {
        return ResponseEntity.ok(supplierCreditNoteService.record(request));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one supplier credit note")
    public ResponseEntity<SupplierCreditNoteResponse> get(@PathVariable String id) {
        return ResponseEntity.ok(supplierCreditNoteService.get(id));
    }

    @GetMapping
    @Operation(summary = "List a vendor's credit notes")
    public ResponseEntity<List<SupplierCreditNoteResponse>> list(@RequestParam("vendorId") String vendorId) {
        return ResponseEntity.ok(supplierCreditNoteService.listForVendor(vendorId));
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel a recorded credit note", description = "Only allowed before any refund has been received against it.")
    public ResponseEntity<SupplierCreditNoteResponse> cancel(@PathVariable String id, @Valid @RequestBody SupplierCreditNoteCancelRequest request) {
        return ResponseEntity.ok(supplierCreditNoteService.cancel(id, request.getReason()));
    }

    @PostMapping("/{id}/refund")
    @Operation(summary = "Record cash actually received back from the supplier")
    public ResponseEntity<SupplierCreditNoteResponse> receiveRefund(@PathVariable String id, @Valid @RequestBody SupplierCreditNoteRefundRequest request) {
        return ResponseEntity.ok(supplierCreditNoteService.receiveRefund(id, request));
    }
}
