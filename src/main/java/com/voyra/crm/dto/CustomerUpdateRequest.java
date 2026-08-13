package com.voyra.crm.dto;

import com.voyra.crm.enums.CustomerStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/** Patch semantics - every field optional, only non-null fields are applied. */
@Data
@Schema(description = "Request body for updating a Customer's profile")
public class CustomerUpdateRequest {

    @Size(max = 150, message = "Name must be 150 characters or fewer")
    private String name;

    @Email(message = "Email must be a valid email address")
    @Size(max = 150, message = "Email must be 150 characters or fewer")
    private String email;

    @Size(max = 6, message = "Country code must be 6 characters or fewer")
    private String countryCode;

    @Size(max = 20, message = "Phone must be 20 characters or fewer")
    private String phone;

    private LocalDate dob;

    @Size(max = 30, message = "Gender must be 30 characters or fewer")
    private String gender;

    @Size(max = 100, message = "City must be 100 characters or fewer")
    private String city;

    @Size(max = 100, message = "Country must be 100 characters or fewer")
    private String country;

    @Size(max = 100, message = "Nationality must be 100 characters or fewer")
    private String nationality;

    @Size(max = 20, message = "Passport number must be 20 characters or fewer")
    private String passportNumber;

    private LocalDate passportExpiry;

    @Size(max = 100, message = "Preferred airline must be 100 characters or fewer")
    private String preferredAirline;

    @Size(max = 30, message = "Preferred cabin must be 30 characters or fewer")
    private String preferredCabin;

    private CustomerStatus status;
    private List<String> tags;
}
