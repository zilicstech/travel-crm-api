package com.voyra.crm.exception;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Minimal fixture, used only by {@link GlobalExceptionHandlerTest} to trigger each mapped exception type. */
@RestController
public class GlobalExceptionHandlerFixtureController {

    @GetMapping("/test/too-long")
    public String tooLong() {
        throw new org.springframework.dao.DataIntegrityViolationException("could not execute statement",
                new RuntimeException("ERROR: value too long for type character varying(150)"));
    }

    @GetMapping("/test/illegal-argument")
    public String illegalArgument() {
        throw new IllegalArgumentException("bad input");
    }

    @GetMapping("/test/illegal-state")
    public String illegalState() {
        throw new IllegalStateException("bad state");
    }

    @GetMapping("/test/insufficient-balance")
    public String insufficientBalance() {
        throw new InsufficientBalanceException(new java.math.BigDecimal("600.00"));
    }

    @GetMapping("/test/receivable-due")
    public String receivableDue() {
        throw new ReceivableDueException();
    }

    @GetMapping("/test/issuing-agent")
    public String issuingAgent() {
        throw new BookingRequestException("PAX_MIX_CHANGED", "The fare was reviewed for other travellers");
    }

    @GetMapping("/test/price-changed")
    public String priceChanged() {
        throw new BookingConflictException("PRICE_CHANGED", "The fare changed", java.util.Map.of("displayedFare", new java.math.BigDecimal("4600.00")));
    }

    @GetMapping("/test/fare-unavailable")
    public String fareUnavailable() {
        throw new FareUnavailableException("gone");
    }

    @GetMapping("/test/access-denied")
    public String accessDenied() {
        throw new AccessDeniedException("denied");
    }

    @GetMapping("/test/supplier-failure")
    public String supplierFailure() {
        throw new SupplierException("Connection to https://apitest.tripjack.com/api/v1/search timed out",
                new java.io.IOException("timeout"));
    }

    @GetMapping("/test/upload-too-large")
    public String uploadTooLarge() {
        throw new org.springframework.web.multipart.MaxUploadSizeExceededException(10L * 1024 * 1024);
    }

    @GetMapping("/test/query")
    public String query(@org.springframework.web.bind.annotation.RequestParam("day") java.time.LocalDate day) {
        return "ok";
    }

    @PostMapping("/test/validate")
    public String validate(@Valid @RequestBody FixtureRequest request) {
        return "ok";
    }

    @Data
    public static class FixtureRequest {
        @NotBlank(message = "Name is required")
        private String name;
    }
}
