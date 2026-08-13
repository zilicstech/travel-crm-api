package com.voyra.crm.dto;

import com.voyra.crm.enums.CustomerStatus;
import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(description = "A Customer's list-view profile")
public class CustomerResponse {

    @Schema(example = "K3M8P1")
    private String id;

    @Schema(example = "CB9Y0N")
    private String agentId;

    @Schema(description = "Denormalized snapshot, live-synced on agent rename", example = "Liam Smith")
    private String agentName;

    @Schema(example = "Jane Doe")
    private String name;

    @Schema(example = "jane.doe@example.com")
    private String email;

    @Schema(example = "+91")
    private String countryCode;

    @Schema(example = "9876543210")
    private String phone;

    @Schema(example = "1990-05-20")
    private LocalDate dob;

    @Schema(example = "Female")
    private String gender;

    @Schema(example = "Mumbai")
    private String city;

    @Schema(example = "India")
    private String country;

    @Schema(example = "Indian")
    private String nationality;

    @Schema(example = "M1234567")
    private String passportNumber;

    @Schema(example = "2032-05-20")
    private LocalDate passportExpiry;

    @Schema(example = "Emirates")
    private String preferredAirline;

    @Schema(example = "Economy")
    private String preferredCabin;

    @Schema(example = "VIP")
    private CustomerStatus status;

    @Schema(description = "Free-form CRM tags")
    private List<String> tags;

    @Schema(example = "2026-08-13T09:15:22")
    private LocalDateTime createdDate;
}
