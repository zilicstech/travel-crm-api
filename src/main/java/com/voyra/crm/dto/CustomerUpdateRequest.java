package com.voyra.crm.dto;

import com.voyra.crm.enums.CustomerStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/** Patch semantics - every field optional, only non-null fields are applied. */
@Data
@Schema(description = "Request body for updating a Customer's profile")
public class CustomerUpdateRequest {

    private String name;
    private String email;
    private String countryCode;
    private String phone;
    private LocalDate dob;
    private String gender;
    private String city;
    private String country;
    private String nationality;
    private String passportNumber;
    private LocalDate passportExpiry;
    private String preferredAirline;
    private String preferredCabin;
    private CustomerStatus status;
    private List<String> tags;
}
