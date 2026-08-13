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

    @Schema(description = "Owner-only: assign the customer to a specific agent. Ignored for the AGENT role (always self).", example = "CB9Y0N")
    private String agentId;

    // Step 1 - Personal Details
    @NotBlank(message = "Name is required")
    @Schema(example = "Jane Doe")
    private String name;

    @Schema(example = "+91")
    private String countryCode;

    @NotBlank(message = "Phone is required")
    @Schema(example = "9876543210")
    private String phone;

    @NotBlank(message = "Email is required")
    @Schema(example = "jane.doe@example.com")
    private String email;

    @Schema(example = "1990-05-20")
    private LocalDate dob;

    @Schema(example = "Female")
    private String gender;

    @Schema(example = "Mumbai")
    private String city;

    @Schema(example = "India")
    private String country;

    // Step 2 - Travel Profile
    @Schema(example = "Indian")
    private String nationality;

    @Schema(example = "M1234567")
    private String passportNumber;

    @Schema(example = "2032-05-20")
    private LocalDate passportExpiry;

    @Schema(example = "Emirates")
    private String preferredAirline;

    @Schema(example = "Economy")
    private String preferredCabin;

    // Step 3 - CRM Tags & Notes
    @NotNull(message = "Status is required")
    @Schema(example = "LEAD")
    private CustomerStatus status;

    @Schema(description = "Free-form CRM tags, stored as a native array")
    private List<String> tags;

    @Schema(description = "Optional - becomes the first interaction log entry if non-blank")
    private String notes;
}
