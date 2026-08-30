package com.voyra.crm.dto;

import com.voyra.crm.enums.MemberRelation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

@Data
@Schema(description = "Patch semantics: a null field means 'not supplied' and is left unchanged. "
        + "Renaming a member re-syncs the member_name snapshot on every lead manifest row they "
        + "appear on, in the same transaction.")
public class MemberUpdateRequest {

    @Size(max = 150, message = "Name must be 150 characters or fewer")
    @Schema(example = "Ankita Sharma")
    private String name;

    @Schema(description = "Cannot be changed to or from SELF; the primary member is fixed for the client's life",
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

    @Schema(example = "1991-11-03")
    private LocalDate dob;

    @Size(max = 30, message = "Gender must be 30 characters or fewer")
    @Schema(example = "Female")
    private String gender;

    @Size(max = 100, message = "Nationality must be 100 characters or fewer")
    @Schema(example = "Indian")
    private String nationality;

    @Size(max = 20, message = "Passport number must be 20 characters or fewer")
    @Schema(example = "M7654321")
    private String passportNumber;

    @Schema(example = "2031-08-14")
    private LocalDate passportExpiry;
}
