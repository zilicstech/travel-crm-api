package com.voyra.crm.controller;

import com.voyra.crm.dto.BookingDocumentCreateRequest;
import com.voyra.crm.dto.BookingDocumentResponse;
import com.voyra.crm.service.BookingDocumentService;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/** Voucher documents attached to a booking - an e-ticket, a hotel voucher, an insurance certificate. */
@Slf4j
@RestController
@RequestMapping("/api/bookings/{bookingId}/documents")
@RequiredArgsConstructor
@Tag(name = "Booking Documents", description = "Voucher documents attached to a booking")
@PreAuthorize("hasAnyRole('AGENCY_OWNER', 'AGENT')")
public class BookingDocumentController {

    private final BookingDocumentService bookingDocumentService;

    @PostMapping
    @Operation(summary = "Add a document")
    public ResponseEntity<BookingDocumentResponse> addDocument(@PathVariable String bookingId,
                                                                @Valid @RequestBody BookingDocumentCreateRequest request) {
        return ResponseEntity.ok(bookingDocumentService.addDocument(bookingId, request));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('AGENCY_OWNER', 'AGENT', 'ACCOUNTANT')")
    @Operation(summary = "This booking's documents")
    public ResponseEntity<List<BookingDocumentResponse>> listDocuments(@PathVariable String bookingId) {
        return ResponseEntity.ok(bookingDocumentService.listForBooking(bookingId));
    }

    @DeleteMapping("/{documentId}")
    @Operation(summary = "Remove a document")
    public ResponseEntity<Void> deleteDocument(@PathVariable String bookingId, @PathVariable String documentId) {
        bookingDocumentService.deleteDocument(bookingId, documentId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/{documentId}/file", consumes = "multipart/form-data")
    @Operation(summary = "Attach or replace a document's file")
    public ResponseEntity<BookingDocumentResponse> attachFile(@PathVariable String bookingId,
                                                               @PathVariable String documentId,
                                                               @RequestPart("file") MultipartFile file) {
        return ResponseEntity.ok(bookingDocumentService.attachFile(bookingId, documentId, file));
    }

    @GetMapping("/{documentId}/file")
    @PreAuthorize("hasAnyRole('AGENCY_OWNER', 'AGENT', 'ACCOUNTANT')")
    @Operation(summary = "Download or view a document's stored file")
    public ResponseEntity<Resource> downloadFile(@PathVariable String bookingId, @PathVariable String documentId) {
        BookingDocumentService.BookingDocumentFileContent content = bookingDocumentService.downloadFile(bookingId, documentId);
        MediaType mediaType = content.contentType() != null
                ? MediaType.parseMediaType(content.contentType())
                : MediaType.APPLICATION_OCTET_STREAM;
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + content.filename() + "\"")
                .body(content.resource());
    }
}
