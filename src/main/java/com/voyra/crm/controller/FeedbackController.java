package com.voyra.crm.controller;

import com.voyra.crm.dto.FeedbackResponse;
import com.voyra.crm.service.FeedbackService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Feedback a customer has submitted against their bookings - read side, Client 360 tab. */
@RestController
@RequestMapping("/api/clients/{clientId}/feedback")
@RequiredArgsConstructor
@Tag(name = "Feedback", description = "Customer feedback submitted through the public feedback link")
@PreAuthorize("hasAnyRole('AGENCY_OWNER', 'AGENT')")
public class FeedbackController {

    private final FeedbackService feedbackService;

    @GetMapping
    @Operation(summary = "This client's submitted feedback")
    public ResponseEntity<List<FeedbackResponse>> list(@PathVariable String clientId) {
        return ResponseEntity.ok(feedbackService.listForClient(clientId));
    }
}
