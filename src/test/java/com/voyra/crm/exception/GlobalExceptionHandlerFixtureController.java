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

    @GetMapping("/test/illegal-argument")
    public String illegalArgument() {
        throw new IllegalArgumentException("bad input");
    }

    @GetMapping("/test/illegal-state")
    public String illegalState() {
        throw new IllegalStateException("bad state");
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
