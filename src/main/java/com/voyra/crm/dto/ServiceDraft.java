package com.voyra.crm.dto;

import com.voyra.crm.enums.FlightCabin;
import com.voyra.crm.enums.FlightTripType;
import com.voyra.crm.enums.ServiceType;
import com.voyra.crm.enums.VisaApplicationStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

@Data
@Schema(description = "One service instance to create. POST /api/leads/{id}/services takes a "
        + "list of these - always creating NEW instances, one call whether it is the lead's "
        + "first flight or its fourth. Only the fields for the given type are read.")
public class ServiceDraft {

    @NotNull(message = "Service type is required")
    @Schema(example = "FLIGHT")
    private ServiceType type;

    @Schema(example = "2026-09-20")
    private LocalDate dueDate;

    @Schema(description = "Standing preferences, matched by name against agency_setting")
    private List<String> preferences;

    @Schema(example = "ONE_WAY")
    private FlightTripType flightTripType;

    @Schema(example = "ECONOMY")
    private FlightCabin flightCabin;

    @Schema(description = "At most 8 sectors; a round trip's return leg mirrors the outbound")
    private List<FlightSectorDto> flightSectors;

    @Schema(example = "Bangkok")
    private String hotelCity;

    @Schema(description = "ISO alpha-2 country code, picked alongside hotelCity", example = "TH")
    private String hotelCountryCode;

    @Schema(example = "2026-10-01")
    private LocalDate hotelCheckIn;

    @Schema(description = "Derived from checkIn + nights when omitted", example = "2026-10-05")
    private LocalDate hotelCheckOut;

    @Schema(example = "4")
    private Integer hotelNights;

    @Schema(example = "1")
    private Integer hotelRooms;

    @Schema(example = "Mumbai")
    private String visaSourceCity;

    @Schema(example = "India")
    private String visaSourceCountry;

    @Schema(example = "Thailand")
    private String visaCountry;

    @Schema(example = "2026-10-01")
    private LocalDate visaIntendedTravelDate;

    @Schema(example = "2026-09-10")
    private LocalDate visaAppointmentDate;

    @Schema(description = "Visa services only; a new Visa service starts NOT_APPLIED and an update without it keeps the "
            + "current status", example = "APPLIED")
    private VisaApplicationStatus visaStatus;

    @Schema(example = "4 Seater (Sedan)")
    private String transferVehicleType;

    @Schema(example = "Airport")
    private String transferPickup;

    @Schema(example = "Hotel")
    private String transferDropoff;

    @Schema(example = "2026-10-01")
    private LocalDate transferDate;

    @Schema(description = "Local clock time, HH:mm", example = "14:30")
    private String transferTime;

    @Schema(example = "3")
    private Integer transferPassengers;

    @Schema(description = "Miscellaneous services only: what the service is", example = "Travel insurance for 4")
    @Size(max = 500, message = "Write at most 500 characters")
    private String miscDetails;

    @Schema(description = "Miscellaneous services only: its sub-category, from Settings (plain text)", example = "Railway ticket")
    @Size(max = 150, message = "Write at most 150 characters")
    private String miscCategory;

    @Schema(description = "Insurance services only: who the policy covers", example = "INDIVIDUAL")
    private com.voyra.crm.enums.InsuranceType insuranceType;

    @Schema(description = "Insurance services only: the country the traveller leaves from", example = "India")
    @Size(max = 80, message = "Write at most 80 characters")
    private String insuranceFromCountry;

    @Schema(description = "Insurance services only: the countries travelled, comma separated", example = "France, Italy")
    @Size(max = 500, message = "Write at most 500 characters")
    private String insuranceCountries;

    @Schema(description = "Insurance services only: cover starts", example = "2026-12-01")
    private java.time.LocalDate insuranceStart;

    @Schema(description = "Insurance services only: cover ends", example = "2026-12-15")
    private java.time.LocalDate insuranceEnd;

    @Schema(description = "Insurance services only: persons covered", example = "2")
    @jakarta.validation.constraints.Min(value = 1, message = "At least 1 person")
    @jakarta.validation.constraints.Max(value = 99, message = "At most 99 persons")
    private Integer insurancePersons;
}
