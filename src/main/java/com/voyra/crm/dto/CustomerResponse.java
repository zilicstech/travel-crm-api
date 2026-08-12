package com.voyra.crm.dto;

import com.voyra.crm.enums.CustomerStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerResponse {

    private String id;
    private String agentId;
    private String agentName;
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
    private LocalDateTime createdDate;
}
