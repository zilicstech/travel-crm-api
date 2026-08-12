package com.voyra.crm.dto;

import com.voyra.crm.enums.CustomerStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

@Data
@Schema(description = "Request body for the 3-step Add Customer wizard")
public class CustomerCreateRequest {

    @Schema(description = "Owner-only: assign the customer to a specific agent. Ignored for the AGENT role (always self).")
    private String agentId;

    // Step 1 - Personal Details
    @NotBlank(message = "Name is required")
    private String name;

    @Schema(example = "+91")
    private String countryCode;

    @NotBlank(message = "Phone is required")
    private String phone;

    @NotBlank(message = "Email is required")
    private String email;

    private LocalDate dob;
    private String gender;
    private String city;
    private String country;

    // Step 2 - Travel Profile
    private String nationality;
    private String passportNumber;
    private LocalDate passportExpiry;
    private String preferredAirline;
    private String preferredCabin;

    // Step 3 - CRM Tags & Notes
    @NotNull(message = "Status is required")
    private CustomerStatus status;

    private List<String> tags;

    @Schema(description = "Optional - becomes the first interaction log entry if non-blank")
    private String notes;
}
