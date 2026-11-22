package com.voyra.crm.controller;

import com.voyra.crm.dto.BoardingPassRowResponse;
import com.voyra.crm.dto.PagedResponse;
import com.voyra.crm.enums.BoardingPassStatus;
import com.voyra.crm.service.BoardingPassService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/boarding-passes")
@RequiredArgsConstructor
@Tag(name = "Boarding passes", description = "Flights travelling on a day and whether their boarding pass was generated")
@PreAuthorize("hasAnyRole('AGENCY_OWNER', 'AGENT')")
public class BoardingPassController {

    private final BoardingPassService boardingPassService;

    @GetMapping
    @Operation(summary = "Flights travelling on a day (default tomorrow, India time)", description = "pending: only those with nothing done yet")
    public ResponseEntity<PagedResponse<BoardingPassRowResponse>> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "true") boolean pending,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok(boardingPassService.list(date, pending, PageRequest.of(page, size)));
    }

    @GetMapping("/booking/{bookingId}")
    @Operation(summary = "Every flight date of one booking with its boarding pass")
    public ResponseEntity<List<BoardingPassRowResponse>> forBooking(@PathVariable String bookingId) {
        return ResponseEntity.ok(boardingPassService.forBooking(bookingId));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Mark a day's boarding pass generated (optional proof file) or not needed (optional reason)")
    public ResponseEntity<BoardingPassRowResponse> mark(@RequestParam String bookingId,
                                                        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate travelDate,
                                                        @RequestParam BoardingPassStatus status,
                                                        @RequestParam(required = false) String reason,
                                                        @RequestPart(name = "file", required = false) MultipartFile file) {
        return ResponseEntity.ok(boardingPassService.mark(bookingId, travelDate, status, reason, file));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Set a day's boarding pass back to still to do")
    public ResponseEntity<Void> reset(@PathVariable String id) {
        boardingPassService.reset(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/file")
    @Operation(summary = "The proof file, to download and share")
    public ResponseEntity<Resource> file(@PathVariable String id) {
        BoardingPassService.ProofFile f = boardingPassService.file(id);
        return ResponseEntity.ok()
                .contentType(f.contentType() != null ? MediaType.parseMediaType(f.contentType()) : MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + f.fileName() + "\"")
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(f.resource());
    }
}
