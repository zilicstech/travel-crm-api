package com.voyra.crm.dto;

import com.voyra.crm.enums.MemberRelation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

@Data
@Schema(description = "Adds a person to a client's roster - a family member for a B2C client, "
        + "or a group member for a B2B client. Only the name is required: an agent taking a "
        + "phone enquiry rarely has passport details yet, and the roster is expected to harden "
        + "over the life of the enquiry rather than be complete up front.")
public class MemberCreateRequest {

    @NotBlank(message = "Name is required")
    @Size(max = 150, message = "Name must be 150 characters or fewer")
    @Schema(example = "Ankita Sharma")
    private String name;

    @NotNull(message = "Relation is required")
    @Schema(description = "How this person relates to the client. SELF is reserved for the primary member.",
            example = "SPOUSE")
    private MemberRelation relation;

    @Email(message = "Enter a valid email address")
    @Size(max = 150, message = "Email must be 150 characters or fewer")
    @Schema(example = "ankita.sharma@example.com")
    private String email;

    @Size(max = 6, message = "Country code must be 6 characters or fewer")
    @Schema(example = "+91")
    private String countryCode;

    @Size(max = 20, message = "Phone must be 20 characters or fewer")
    @Schema(example = "9876543211")
    private String phone;

    @Schema(description = "Used to derive pax type at the travel date; never stored as an age", example = "1991-11-03")
    private LocalDate dob;

    @Size(max = 30, message = "Gender must be 30 characters or fewer")
    @Schema(example = "Female")
    private String gender;

    @Size(max = 100, message = "Nationality must be 100 characters or fewer")
    @Schema(description = "Required before an international booking or visa filing", example = "Indian")
    private String nationality;

    @Size(max = 20, message = "Passport number must be 20 characters or fewer")
    @Schema(description = "Required before an international booking or visa filing", example = "M7654321")
    private String passportNumber;

    @Schema(example = "2031-08-14")
    private LocalDate passportExpiry;
}
