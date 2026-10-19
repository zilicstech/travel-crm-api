package com.voyra.crm.dto;

import com.voyra.crm.util.ValidationPatterns;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Sizes match the inquiry table columns. "Email or phone required" is checked in the service. */
@Data
@Schema(description = "A sales inquiry submitted from a public marketing-site form")
public class InquiryCreateRequest {

    @NotBlank(message = "Source is required")
    @Size(max = 40, message = "Source must be 40 characters or fewer")
    @Pattern(regexp = "^[a-z0-9-]*$", message = "Source must be lower-case letters, digits or dashes")
    @Schema(description = "Which form sent it", example = "pricing-modal")
    private String source;

    @Size(max = 150, message = "Name must be 150 characters or fewer")
    @Schema(example = "John Davis")
    private String name;

    @Email(message = "Enter a valid email address")
    @Size(max = 150, message = "Email must be 150 characters or fewer")
    @Schema(description = "Email or phone is required", example = "john@globalexplorer.com")
    private String email;

    @Size(max = 30, message = "Phone must be 30 characters or fewer")
    @Pattern(regexp = ValidationPatterns.PHONE, message = ValidationPatterns.PHONE_MESSAGE)
    @Schema(description = "Email or phone is required", example = "+91 98765 43210")
    private String phone;

    @Size(max = 200, message = "Agency name must be 200 characters or fewer")
    @Schema(description = "Agency / company name as typed", example = "Global Explorer Travels (8 agents)")
    private String company;

    @Size(max = 60, message = "Plan must be 60 characters or fewer")
    @Schema(description = "Pricing plan the visitor asked about, if any", example = "Growth Plan")
    private String plan;

    @Size(max = 2000, message = "Message must be 2000 characters or fewer")
    @Schema(example = "We handle about 300 leads a month and need visa tracking.")
    private String message;

    @Size(max = 200)
    @Schema(description = "Honeypot - a hidden field people never fill; a non-empty value means a bot and the inquiry is silently dropped",
            example = "")
    private String website;
}
