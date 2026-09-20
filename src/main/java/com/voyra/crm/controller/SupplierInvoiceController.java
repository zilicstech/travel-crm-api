package com.voyra.crm.controller;

import com.voyra.crm.dto.SupplierInvoiceCancelRequest;
import com.voyra.crm.dto.SupplierInvoiceDraftRequest;
import com.voyra.crm.dto.SupplierInvoiceListItemResponse;
import com.voyra.crm.dto.SupplierInvoiceResponse;
import com.voyra.crm.enums.SupplierInvoiceStatus;
import com.voyra.crm.service.SupplierInvoiceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/** Supplier bills - accounts payable. Owner/Accountant only, never agent-scoped (payables are not agent data). */
@Slf4j
@RestController
@RequestMapping("/api/accounts/payables/invoices")
@RequiredArgsConstructor
@Tag(name = "Payables - Supplier Bills", description = "What a vendor billed the agency")
@PreAuthorize("hasAnyRole('AGENCY_OWNER', 'ACCOUNTANT')")
public class SupplierInvoiceController {

    private final SupplierInvoiceService supplierInvoiceService;

    @PostMapping
    @Operation(summary = "Create a draft supplier bill")
    public ResponseEntity<SupplierInvoiceResponse> create(@Valid @RequestBody SupplierInvoiceDraftRequest request) {
        return ResponseEntity.ok(supplierInvoiceService.createDraft(request));
    }

    @GetMapping
    @Operation(summary = "List supplier bills")
    public ResponseEntity<List<SupplierInvoiceListItemResponse>> list(
            @RequestParam(value = "vendorId", required = false) String vendorId,
            @RequestParam(value = "status", required = false) SupplierInvoiceStatus status,
            @RequestParam(value = "bookingId", required = false) String bookingId) {
        return ResponseEntity.ok(supplierInvoiceService.list(vendorId, status, bookingId));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one supplier bill with its line items")
    public ResponseEntity<SupplierInvoiceResponse> get(@PathVariable String id) {
        return ResponseEntity.ok(supplierInvoiceService.get(id));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Replace a draft's header and line list", description = "DRAFT only - replaces every line.")
    public ResponseEntity<SupplierInvoiceResponse> update(@PathVariable String id, @Valid @RequestBody SupplierInvoiceDraftRequest request) {
        return ResponseEntity.ok(supplierInvoiceService.updateDraft(id, request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a draft")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        supplierInvoiceService.deleteDraft(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/approve")
    @Operation(summary = "Approve a draft bill", description = "Books the payable to the vendor ledger. The recorded GST is validated against the vendor's state, never recomputed.")
    public ResponseEntity<SupplierInvoiceResponse> approve(@PathVariable String id) {
        return ResponseEntity.ok(supplierInvoiceService.approve(id));
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel a draft or approved bill with no payments against it")
    public ResponseEntity<SupplierInvoiceResponse> cancel(@PathVariable String id, @Valid @RequestBody SupplierInvoiceCancelRequest request) {
        return ResponseEntity.ok(supplierInvoiceService.cancel(id, request.getReason()));
    }

    @PostMapping(value = "/{id}/file", consumes = "multipart/form-data")
    @Operation(summary = "Attach or replace the bill's own file")
    public ResponseEntity<SupplierInvoiceResponse> attachFile(@PathVariable String id, @RequestPart("file") MultipartFile file) {
        return ResponseEntity.ok(supplierInvoiceService.attachFile(id, file));
    }

    @GetMapping("/{id}/file")
    @Operation(summary = "Download or view the bill's stored file")
    public ResponseEntity<Resource> downloadFile(@PathVariable String id) {
        SupplierInvoiceService.SupplierInvoiceFileContent content = supplierInvoiceService.downloadFile(id);
        MediaType mediaType = content.contentType() != null
                ? MediaType.parseMediaType(content.contentType())
                : MediaType.APPLICATION_OCTET_STREAM;
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + content.filename() + "\"")
                .body(content.resource());
    }
}
