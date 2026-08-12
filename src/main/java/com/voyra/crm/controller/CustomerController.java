package com.voyra.crm.controller;

import com.voyra.crm.dto.CustomerCreateRequest;
import com.voyra.crm.dto.CustomerDetailResponse;
import com.voyra.crm.dto.CustomerLookupResponse;
import com.voyra.crm.dto.CustomerResponse;
import com.voyra.crm.dto.CustomerUpdateRequest;
import com.voyra.crm.service.CustomerService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Shared between AGENCY_OWNER and AGENT: unlike Lead (owner reassigns leads, agent cannot -
 * a genuinely different rule per role), Customer read/write rules are identical for both
 * roles - only the visible result set differs (own customers vs. whole agency), which
 * {@link CustomerService} resolves per-request from the principal. One controller here is a
 * deliberate, narrower exception to the blueprint's audience-split convention (§5.3), not a
 * blanket departure from it - see OwnerAgentController for a case that genuinely does split.
 */
@Slf4j
@RestController
@RequestMapping("/api/customers")
@RequiredArgsConstructor
@Tag(name = "Customers", description = "Customer CRM - shared between Agency Owner and Agent, scoped per caller")
@PreAuthorize("hasAnyRole('AGENCY_OWNER', 'AGENT')")
public class CustomerController {

    private final CustomerService customerService;

    @PostMapping
    @Operation(summary = "Add a new customer (3-step wizard payload)")
    public ResponseEntity<CustomerResponse> createCustomer(@Valid @RequestBody CustomerCreateRequest request) {
        return ResponseEntity.ok(customerService.createCustomer(request));
    }

    @GetMapping
    @Operation(summary = "List customers", description = "Agents see only their own customers; Owners see the whole agency.")
    public ResponseEntity<List<CustomerResponse>> listCustomers(
            @RequestParam(value = "search", required = false) String search) {
        return ResponseEntity.ok(customerService.listCustomers(search));
    }

    @GetMapping("/lookup")
    @Operation(summary = "Auto-lookup a customer by phone", description = "Powers the Add Lead wizard's phone-match step.")
    public ResponseEntity<CustomerLookupResponse> lookup(
            @RequestParam(value = "phone") String phone,
            @RequestParam(value = "country_code", required = false) String countryCode) {
        return ResponseEntity.ok(customerService.lookupByPhone(countryCode, phone));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Customer 360 profile")
    public ResponseEntity<CustomerDetailResponse> getCustomer(@PathVariable String id) {
        return ResponseEntity.ok(customerService.getCustomer(id));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a customer's profile")
    public ResponseEntity<CustomerResponse> updateCustomer(@PathVariable String id,
                                                            @Valid @RequestBody CustomerUpdateRequest request) {
        return ResponseEntity.ok(customerService.updateCustomer(id, request));
    }
}
