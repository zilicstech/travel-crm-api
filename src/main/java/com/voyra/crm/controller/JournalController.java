package com.voyra.crm.controller;

import com.voyra.crm.dto.JournalEntryResponse;
import com.voyra.crm.dto.JournalPostRequest;
import com.voyra.crm.service.JournalService;
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

/**
 * Manual journal entries - Owner/Accountant only (Rule 1.6.4). Every other journal in the system
 * posts automatically from the document that caused it; this is the one path a human can use
 * directly, and it refuses a line naming a control account (enforced in
 * {@code JournalService#post}) so a manual entry can never desynchronise a control account from
 * its subsidiary ledger.
 */
@Slf4j
@RestController
@RequestMapping("/api/accounts/journals")
@RequiredArgsConstructor
@Tag(name = "Accounts - Journals", description = "Manual double-entry journal entries")
@PreAuthorize("hasAnyRole('AGENCY_OWNER', 'ACCOUNTANT')")
public class JournalController {

    private final JournalService journalService;

    @PostMapping
    @Operation(summary = "Post a manual journal entry", description = "sourceType must be MANUAL. Debits must equal credits exactly across the lines.")
    public ResponseEntity<JournalEntryResponse> post(@Valid @RequestBody JournalPostRequest request) {
        return ResponseEntity.ok(journalService.postManual(request));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one journal entry with its lines")
    public ResponseEntity<JournalEntryResponse> get(@PathVariable String id) {
        return ResponseEntity.ok(journalService.get(id));
    }

    @PostMapping("/{id}/reverse")
    @Operation(summary = "Reverse a posted journal entry", description = "Posts a new entry with every line's debit and credit swapped, and marks the original REVERSED. The original is never edited.")
    public ResponseEntity<JournalEntryResponse> reverse(@PathVariable String id, @RequestParam(required = false) String reason) {
        return ResponseEntity.ok(journalService.toResponse(journalService.reverse(id, reason)));
    }
}
