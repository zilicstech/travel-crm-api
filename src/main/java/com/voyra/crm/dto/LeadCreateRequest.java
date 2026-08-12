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

    @Schema(description = "Owner-only: assign the lead to a specific agent. Ignored for the AGENT role (always self).")
    private String assignedTo;

    // Step 1 - Contact
    private String countryCode;

    @NotBlank(message = "Phone is required")
    private String phone;

    @NotBlank(message = "Name is required")
    private String name;

    private String email;

    @Schema(description = "Set when the phone-lookup step matched an existing customer")
    private String customerId;

    // Step 2 - Trip Details
    @NotBlank(message = "Destination is required")
    private String destination;

    private LocalDate travelDateFrom;
    private LocalDate travelDateTo;

    @NotEmpty(message = "Select at least one category")
    private List<LeadCategory> categories;

    private String budget;
    private LocalDate followUpDate;

    @Schema(defaultValue = "PHONE_CALL")
    private LeadSource source;

    @Schema(defaultValue = "MEDIUM")
    private LeadPriority priority;

    // Step 3 - Guests
    private GuestDetails guestDetails;
}
