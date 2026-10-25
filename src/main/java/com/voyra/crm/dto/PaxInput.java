package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "One traveller on a platform booking; stored encrypted")
public class PaxInput {

    @NotBlank
    @Pattern(regexp = "ADULT|CHILD|INFANT")
    @Schema(description = "ADULT, CHILD or INFANT", example = "ADULT")
    private String type;

    @Size(max = 10)
    @Schema(description = "Title as printed on the ticket", example = "Mr")
    private String title;

    @NotBlank
    @Size(max = 60)
    @Schema(description = "First name as on the ID", example = "Rahul")
    private String firstName;

    @NotBlank
    @Size(max = 60)
    @Schema(description = "Last name as on the ID", example = "Sharma")
    private String lastName;

    @Schema(description = "Date of birth; required for children and infants", example = "1990-04-12")
    private LocalDate dob;

    @Size(max = 20)
    @Schema(description = "Passport number (international only)", example = "N1234567")
    private String passportNo;

    @Schema(description = "Passport expiry (international only)", example = "2031-01-31")
    private LocalDate passportExpiry;

    @Size(max = 2)
    @Schema(description = "Nationality, ISO country code", example = "IN")
    private String nationality;

    @Schema(description = "CRM member this traveller was picked from, if any", example = "m1d2e3f4-0000-0000-0000-000000000001")
    private String memberId;
}
