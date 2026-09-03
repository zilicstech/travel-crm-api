package com.voyra.crm.dto;

import com.voyra.crm.enums.FlightCabin;
import com.voyra.crm.enums.FlightTripType;
import com.voyra.crm.enums.ServiceType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
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
}
