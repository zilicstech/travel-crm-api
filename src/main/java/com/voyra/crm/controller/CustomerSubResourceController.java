package com.voyra.crm.controller;

import com.voyra.crm.dto.DocumentResponse;
import com.voyra.crm.dto.FamilyMemberCreateRequest;
import com.voyra.crm.dto.FamilyMemberResponse;
import com.voyra.crm.dto.InteractionCreateRequest;
import com.voyra.crm.dto.InteractionResponse;
import com.voyra.crm.service.CustomerSubResourceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@RestController
@RequestMapping("/api/customers/{customerId}")
@RequiredArgsConstructor
@Tag(name = "Customers - Sub-resources", description = "Family members, documents, and interaction notes")
@PreAuthorize("hasAnyRole('AGENCY_OWNER', 'AGENT')")
public class CustomerSubResourceController {

    private final CustomerSubResourceService subResourceService;

    @PostMapping("/family-members")
    @Operation(summary = "Add a family member")
    public ResponseEntity<FamilyMemberResponse> addFamilyMember(@PathVariable String customerId,
                                                                  @Valid @RequestBody FamilyMemberCreateRequest request) {
        return ResponseEntity.ok(subResourceService.addFamilyMember(customerId, request));
    }

    @PostMapping(value = "/documents", consumes = "multipart/form-data")
    @Operation(summary = "Upload a customer document (passport, visa, ticket, etc.)")
    public ResponseEntity<DocumentResponse> uploadDocument(@PathVariable String customerId,
                                                            @RequestParam("docType") String docType,
                                                            @RequestPart("file") MultipartFile file) {
        return ResponseEntity.ok(subResourceService.uploadCustomerDocument(customerId, docType, file));
    }

    @DeleteMapping("/documents/{documentId}")
    @Operation(summary = "Delete a customer document")
    public ResponseEntity<Void> deleteDocument(@PathVariable String customerId, @PathVariable String documentId) {
        subResourceService.deleteCustomerDocument(customerId, documentId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/family-members/{familyMemberId}/documents", consumes = "multipart/form-data")
    @Operation(summary = "Upload a document for a specific family member")
    public ResponseEntity<DocumentResponse> uploadFamilyMemberDocument(@PathVariable String customerId,
                                                                        @PathVariable String familyMemberId,
                                                                        @RequestParam("docType") String docType,
                                                                        @RequestPart("file") MultipartFile file) {
        return ResponseEntity.ok(
                subResourceService.uploadFamilyMemberDocument(customerId, familyMemberId, docType, file));
    }

    @PostMapping("/interactions")
    @Operation(summary = "Add a note / log a call on the customer profile")
    public ResponseEntity<InteractionResponse> addInteraction(@PathVariable String customerId,
                                                                @Valid @RequestBody InteractionCreateRequest request) {
        return ResponseEntity.ok(subResourceService.addInteraction(customerId, request));
    }
}
