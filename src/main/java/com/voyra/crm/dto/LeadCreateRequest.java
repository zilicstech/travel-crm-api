package com.voyra.crm.dto;

import com.voyra.crm.enums.LeadPriority;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

@Data
@Schema(description = "Creates a lead against an existing client. Contact details are not "
        + "repeated here - they resolve through the client's primary member, so there is one "
        + "place to correct a phone number. Use the client lookup endpoint first to find or "
        + "create the client.")
public class LeadCreateRequest {

    @NotBlank(message = "Client is required")
    @Schema(description = "The client this enquiry belongs to", example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String clientId;

    @NotBlank(message = "Destination is required")
    @Size(max = 150, message = "Destination must be 150 characters or fewer")
    @Schema(example = "Dubai, UAE")
    private String destination;

    @Schema(example = "2026-09-15")
    private LocalDate travelDateFrom;

    @Schema(example = "2026-09-22")
    private LocalDate travelDateTo;

    @NotEmpty(message = "Select at least one category")
    @Schema(description = "Multi-select, stored as a native array, validated against this "
            + "agency's agency_setting rows of kind TRAVEL_CATEGORY. Determines which traveller "
            + "identity fields the manifest must carry before a booking can be made.")
    private List<String> categories;

    @Size(max = 50, message = "Budget must be 50 characters or fewer")
    @Schema(description = "Free-text budget range as entered by the agent", example = "1,50,000 - 2,00,000")
    private String budget;

    @Schema(example = "2026-08-20")
    private LocalDate followUpDate;

    @Schema(description = "Validated against agency_setting rows of kind LEAD_SOURCE",
            defaultValue = "PHONE_CALL", example = "WEBSITE")
    private String source;

    @Schema(defaultValue = "MEDIUM", example = "HIGH")
    private LeadPriority priority;

    @Schema(description = "Traveller headcount; adults defaults to 1 if not supplied")
    private GuestDetails guestDetails;

    @Schema(description = "What the client asked for, in the agent's words",
            example = "Honeymoon package, wants a desert safari and a beach resort")
    private String leadDescription;

    @Schema(description = "Standing preferences for this trip - airline, cabin, meal, hotel category",
            example = "Emirates preferred, vegetarian meals, 5-star only")
    private String preferences;
}
