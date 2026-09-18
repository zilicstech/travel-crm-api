package com.voyra.crm.controller;

import com.voyra.crm.dto.FeedbackSubmitRequest;
import com.voyra.crm.dto.PublicFeedbackResponse;
import com.voyra.crm.dto.SimpleAckResponse;
import com.voyra.crm.service.PublicFeedbackService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Unauthenticated, customer-facing. "Not found" (unknown/expired token) surfaces through the
 * same IllegalArgumentException -> 400 path as everywhere else (blueprint §6.1) - the message
 * never reveals whether a token ever existed. Never returns a mapped entity - see
 * {@link com.voyra.crm.service.PublicFeedbackTenantService} for the pricing-safe DTO build.
 */
@Slf4j
@RestController
@RequestMapping("/api/public/feedback")
@RequiredArgsConstructor
@Tag(name = "Public - Feedback", description = "Unauthenticated customer-facing feedback submission")
public class PublicFeedbackController {

    private final PublicFeedbackService publicFeedbackService;

    @GetMapping("/{token}")
    @Operation(summary = "View the booking to leave feedback on", description = "No auth required.")
    public ResponseEntity<PublicFeedbackResponse> getFeedbackPage(@PathVariable String token) {
        return ResponseEntity.ok(publicFeedbackService.getFeedbackPage(token));
    }

    @PostMapping("/{token}")
    @Operation(summary = "Submit a rating/comment", description = "No auth required. Idempotent - resubmitting overwrites the same row.")
    public ResponseEntity<SimpleAckResponse> submit(@PathVariable String token,
                                                      @Valid @RequestBody FeedbackSubmitRequest request) {
        publicFeedbackService.submit(token, request);
        return ResponseEntity.ok(SimpleAckResponse.builder().success(true).message("Thank you for your feedback").build());
    }
}
