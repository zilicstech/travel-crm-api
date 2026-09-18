package com.voyra.crm.dto;

import com.voyra.crm.enums.ClientType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

@Data
@Schema(description = "Creates a client and its primary member in one call. A client always has "
        + "exactly one primary member - the person the account represents - so the primary "
        + "member's details are part of this request rather than a second round trip.")
public class ClientCreateRequest {

    @NotBlank(message = "Identifier is required")
    @Size(max = 150, message = "Identifier must be 150 characters or fewer")
    @Schema(description = "Deduplication key: the phone number for a B2C client, or the group / company handle for a B2B client. Must be unique among active clients.",
            example = "9876543210")
    private String identifier;

    @NotBlank(message = "Name is required")
    @Size(max = 150, message = "Name must be 150 characters or fewer")
    @Schema(description = "Display name shown across leads, bookings and invoices", example = "Ajay Sharma")
    private String name;

    @NotNull(message = "Client type is required")
    @Schema(description = "B2C for a direct customer or family, B2B for a group or corporate account", example = "B2C")
    private ClientType type;

    @NotBlank(message = "Primary member name is required")
    @Size(max = 150, message = "Primary member name must be 150 characters or fewer")
    @Schema(description = "Name of the primary member - the agency's point of contact", example = "Ajay Sharma")
    private String primaryMemberName;

    @Email(message = "Enter a valid email address")
    @Size(max = 150, message = "Email must be 150 characters or fewer")
    @Schema(example = "ajay.sharma@example.com")
    private String primaryMemberEmail;

    @Size(max = 6, message = "Country code must be 6 characters or fewer")
    @Schema(example = "+91")
    private String primaryMemberCountryCode;

    @Size(max = 20, message = "Phone must be 20 characters or fewer")
    @Schema(example = "9876543210")
    private String primaryMemberPhone;

    @Schema(description = "Used to derive pax type at the travel date; never stored as an age", example = "1988-04-12")
    private LocalDate primaryMemberDob;

    @Size(max = 30, message = "Gender must be 30 characters or fewer")
    @Schema(example = "Male")
    private String primaryMemberGender;

    @Size(max = 100, message = "Nationality must be 100 characters or fewer")
    @Schema(example = "Indian")
    private String primaryMemberNationality;

    @Size(max = 20, message = "Passport number must be 20 characters or fewer")
    @Schema(example = "M1234567")
    private String primaryMemberPassportNumber;

    @Schema(example = "2032-05-20")
    private LocalDate primaryMemberPassportExpiry;

    @Schema(description = "Set when the agent created this client despite a duplicate-check warning - "
            + "the id of the existing client it might be the same as", example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String possibleDuplicateOf;
}
