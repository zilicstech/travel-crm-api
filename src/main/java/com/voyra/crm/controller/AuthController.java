package com.voyra.crm.controller;

import com.voyra.crm.dto.LoginRequest;
import com.voyra.crm.dto.LoginResponse;
import com.voyra.crm.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "Auth", description = "Login endpoints, one per principal type. The only permitAll API surface.")
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login/platform-admin")
    @Operation(summary = "Platform admin login", description = "SUPER_ADMIN login, cross-tenant platform access.")
    public ResponseEntity<LoginResponse> loginPlatformAdmin(@Valid @RequestBody LoginRequest request) {
        LoginResponse response = authService.loginPlatformAdmin(request);
        return respond(response);
    }

    @PostMapping("/login/owner")
    @Operation(summary = "Agency owner login", description = "AGENCY_OWNER login - the Agency's own record is the login.")
    public ResponseEntity<LoginResponse> loginOwner(@Valid @RequestBody LoginRequest request) {
        LoginResponse response = authService.loginOwner(request);
        return respond(response);
    }

    @PostMapping("/login/agent")
    @Operation(summary = "Travel agent or accountant login", description = "AGENT or ACCOUNTANT login (both stored in the same table), scoped to the caller's own tenant.")
    public ResponseEntity<LoginResponse> loginAgent(@Valid @RequestBody LoginRequest request) {
        LoginResponse response = authService.loginAgent(request);
        return respond(response);
    }

    private ResponseEntity<LoginResponse> respond(LoginResponse response) {
        return response.isSuccess()
                ? ResponseEntity.ok(response)
                : ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
    }
}
