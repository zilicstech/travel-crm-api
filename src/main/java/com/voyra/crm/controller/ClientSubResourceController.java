package com.voyra.crm.controller;

import com.voyra.crm.dto.ActiveStatusUpdateRequest;
import com.voyra.crm.dto.DocumentResponse;
import com.voyra.crm.dto.MemberCreateRequest;
import com.voyra.crm.dto.MemberResponse;
import com.voyra.crm.dto.MemberUpdateRequest;
import com.voyra.crm.service.MemberService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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

/** A client's member roster and each member's identity documents. */
@Slf4j
@RestController
@RequestMapping("/api/clients/{clientId}")
@RequiredArgsConstructor
@Tag(name = "Clients - Members", description = "Roster management and member documents")
@PreAuthorize("hasAnyRole('AGENCY_OWNER', 'AGENT')")
public class ClientSubResourceController {

    private final MemberService memberService;

    @GetMapping("/members")
    @Operation(summary = "List a client's active members",
            description = "This is the picker the Add Lead wizard reads from when choosing travellers.")
    public ResponseEntity<List<MemberResponse>> listMembers(@PathVariable String clientId) {
        return ResponseEntity.ok(memberService.listMembers(clientId));
    }

    @PostMapping("/members")
    @Operation(summary = "Add a person to the client's roster",
            description = "Only a name is required; passport and other identity fields can be filled in "
                    + "later as the enquiry firms up.")
    public ResponseEntity<MemberResponse> addMember(@PathVariable String clientId,
                                                    @Valid @RequestBody MemberCreateRequest request) {
        return ResponseEntity.ok(memberService.addMember(clientId, request));
    }

    @PutMapping("/members/{memberId}")
    @Operation(summary = "Update a member",
            description = "Renaming re-syncs the member name snapshot on every lead manifest they appear on.")
    public ResponseEntity<MemberResponse> updateMember(@PathVariable String clientId,
                                                       @PathVariable String memberId,
                                                       @Valid @RequestBody MemberUpdateRequest request) {
        return ResponseEntity.ok(memberService.updateMember(clientId, memberId, request));
    }

    @PatchMapping("/members/{memberId}/status")
    @Operation(summary = "Activate or deactivate a member",
            description = "Deactivation rather than deletion, so past lead manifests keep resolving. "
                    + "The primary member cannot be deactivated.")
    public ResponseEntity<MemberResponse> updateMemberStatus(@PathVariable String clientId,
                                                             @PathVariable String memberId,
                                                             @Valid @RequestBody ActiveStatusUpdateRequest request) {
        return ResponseEntity.ok(memberService.updateStatus(clientId, memberId, request.getIsActive()));
    }

    @PostMapping(value = "/members/{memberId}/documents", consumes = "multipart/form-data")
    @Operation(summary = "Upload a document for a member (passport, visa, aadhaar)")
    public ResponseEntity<DocumentResponse> uploadDocument(@PathVariable String clientId,
                                                           @PathVariable String memberId,
                                                           @RequestParam("docType") String docType,
                                                           @RequestPart("file") MultipartFile file) {
        return ResponseEntity.ok(memberService.uploadDocument(clientId, memberId, docType, file));
    }

    @DeleteMapping("/members/{memberId}/documents/{documentId}")
    @Operation(summary = "Delete a member document")
    public ResponseEntity<Void> deleteDocument(@PathVariable String clientId,
                                               @PathVariable String memberId,
                                               @PathVariable String documentId) {
        memberService.deleteDocument(clientId, memberId, documentId);
        return ResponseEntity.noContent().build();
    }
}
