package com.voyra.crm.dto;

import com.voyra.crm.enums.LeadCategory;
import com.voyra.crm.enums.LeadPriority;
import com.voyra.crm.enums.LeadSource;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

@Data
@Schema(description = "Request body for the 3-step Add Lead wizard")
public class LeadCreateRequest {

    @Schema(description = "Owner-only: assign the lead to a specific agent. Ignored for the AGENT role (always self).", example = "CB9Y0N")
    private String assignedTo;

    // Step 1 - Contact
    @Schema(example = "+91")
    private String countryCode;

    @NotBlank(message = "Phone is required")
    @Schema(example = "9876543210")
    private String phone;

    @NotBlank(message = "Name is required")
    @Schema(example = "Jane Doe")
    private String name;

    @Schema(example = "jane.doe@example.com")
    private String email;

    @Schema(description = "Set when the phone-lookup step matched an existing customer", example = "K3M8P1")
    private String customerId;

    // Step 2 - Trip Details
    @NotBlank(message = "Destination is required")
    @Schema(example = "Dubai, UAE")
    private String destination;

    @Schema(example = "2026-09-15")
    private LocalDate travelDateFrom;

    @Schema(example = "2026-09-22")
    private LocalDate travelDateTo;

    @NotEmpty(message = "Select at least one category")
    @Schema(description = "Multi-select, stored as a native array")
    private List<LeadCategory> categories;

    @Schema(description = "Free-text budget range as entered by the agent", example = "₹1,50,000 - ₹2,00,000")
    private String budget;

    @Schema(example = "2026-08-20")
    private LocalDate followUpDate;

    @Schema(defaultValue = "PHONE_CALL", example = "WEBSITE")
    private LeadSource source;

    @Schema(defaultValue = "MEDIUM", example = "HIGH")
    private LeadPriority priority;

    // Step 3 - Guests
    private GuestDetails guestDetails;
}
