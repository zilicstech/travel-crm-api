package com.voyra.crm.dto;

import com.voyra.crm.util.ValidationPatterns;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Sizes match the agency_application columns. */
@Data
@Schema(description = "A public application from an agency to join the platform")
public class AgencyApplicationCreateRequest {

    @NotBlank(message = "Agency name is required")
    @Size(max = 150, message = "Agency name must be 150 characters or fewer")
    @Schema(example = "Sky Trips")
    private String agencyName;

    @Size(max = 200, message = "Legal name must be 200 characters or fewer")
    @Schema(example = "Sky Trips Private Limited")
    private String legalName;

    @Size(max = 200, message = "Trade name must be 200 characters or fewer")
    @Schema(example = "Sky Trips Holidays")
    private String tradeName;

    @NotBlank(message = "PAN is required")
    @Pattern(regexp = "[A-Z]{5}[0-9]{4}[A-Z]", message = "Enter a valid 10-character PAN in capitals")
    @Schema(example = "ABCDE1234F")
    private String pan;

    @Size(max = 15, message = "GSTIN must be 15 characters or fewer")
    @Pattern(regexp = ValidationPatterns.GSTIN, message = ValidationPatterns.GSTIN_MESSAGE)
    @Schema(example = "29ABCDE1234F1Z5")
    private String gstin;

    @Size(max = 30, message = "CIN must be 30 characters or fewer")
    @Schema(example = "U63040KA2020PTC123456")
    private String cin;

    @Size(max = 500, message = "Address must be 500 characters or fewer")
    @Schema(example = "12 MG Road")
    private String address;

    @Size(max = 100, message = "City must be 100 characters or fewer")
    @Schema(example = "Bengaluru")
    private String city;

    @Pattern(regexp = "^$|^[0-9]{2}$", message = "State code is the 2-digit GST state code")
    @Schema(description = "2-digit GST state code", example = "29")
    private String stateCode;

    @Size(max = 10, message = "Pincode must be 10 characters or fewer")
    @Schema(example = "560001")
    private String pincode;

    @NotBlank(message = "Owner name is required")
    @Size(max = 150, message = "Owner name must be 150 characters or fewer")
    @Schema(example = "Asha Rao")
    private String ownerName;

    @NotBlank(message = "Owner email is required")
    @Email(message = "Enter a valid email address")
    @Size(max = 150, message = "Email must be 150 characters or fewer")
    @Schema(description = "Becomes the owner's login once approved", example = "asha@skytrips.in")
    private String ownerEmail;

    @NotBlank(message = "Owner phone is required")
    @Size(max = 30, message = "Phone must be 30 characters or fewer")
    @Pattern(regexp = ValidationPatterns.PHONE, message = ValidationPatterns.PHONE_MESSAGE)
    @Schema(example = "+91 98765 43210")
    private String ownerPhone;

    @Size(max = 40, message = "Expected monthly volume must be 40 characters or fewer")
    @Schema(example = "50-100 bookings")
    private String expectedMonthlyVolume;

    @NotBlank(message = "Base currency is required")
    @Pattern(regexp = "^[A-Z]{3}$", message = "Base currency is a 3-letter ISO code")
    @Schema(example = "INR")
    private String baseCurrencyCode = "INR";

    @AssertTrue(message = "Accept the terms to apply")
    @Schema(description = "The applicant accepted the terms and the KYC data processing", example = "true")
    private boolean consent;

    @Size(max = 200)
    @Schema(description = "Honeypot - a hidden field people never fill; a non-empty value means a bot and nothing is saved",
            example = "")
    private String website;

    @Schema(description = "Cloudflare Turnstile token from the apply form; required when the server has TURNSTILE_SECRET set",
            example = "0.AbCdEf...")
    private String captchaToken;
}
