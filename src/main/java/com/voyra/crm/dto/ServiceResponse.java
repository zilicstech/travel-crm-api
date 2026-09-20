package com.voyra.crm.dto;

import com.voyra.crm.enums.FlightCabin;
import com.voyra.crm.enums.FlightTripType;
import com.voyra.crm.enums.LeadStatus;
import com.voyra.crm.enums.ServiceStatus;
import com.voyra.crm.enums.ServiceType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "One service instance in full - both the lead's own Services tab and the "
        + "cross-lead board (GET /api/services) read this same shape")
public class ServiceResponse {

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String id;

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String leadId;

    @Schema(description = "Denormalized so the cross-lead board is one flat SELECT", example = "Ajay Sharma")
    private String clientName;

    @Schema(example = "Dubai, UAE")
    private String leadDestination;

    @Schema(example = "PROPOSAL_SENT")
    private LeadStatus leadStatus;

    @Schema(example = "FLIGHT")
    private ServiceType type;

    @Schema(example = "NOT_STARTED")
    private ServiceStatus status;

    @Schema(description = "Stored, server-derived display name", example = "Flight — BOM–BKK")
    private String label;

    @Schema(example = "0")
    private Integer sortOrder;

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String assignedAgentId;

    @Schema(example = "Liam Smith")
    private String assignedAgentName;

    @Schema(description = "Standing preferences, matched by name against agency_setting")
    private List<String> preferences;

    @Schema(example = "2026-09-20")
    private LocalDate dueDate;

    @Schema(description = "Server-derived per type", example = "2026-10-01")
    private LocalDate dateFrom;

    @Schema(example = "2026-10-08")
    private LocalDate dateTo;

    @Schema(description = "Sum of this service's proposal lines' netCost, excluding a cancelled service")
    private BigDecimal netTotal;

    @Schema(description = "Sum of this service's proposal lines' sellingPrice")
    private BigDecimal sellingTotal;

    @Schema(example = "ONE_WAY")
    private FlightTripType flightTripType;

    @Schema(example = "ECONOMY")
    private FlightCabin flightCabin;

    @Schema(description = "At most 8 sectors")
    private List<FlightSectorDto> flightSectors;

    @Schema(example = "Bangkok")
    private String hotelCity;

    @Schema(description = "ISO alpha-2 country code, picked alongside hotelCity", example = "TH")
    private String hotelCountryCode;

    @Schema(example = "2026-10-01")
    private LocalDate hotelCheckIn;

    @Schema(example = "2026-10-05")
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

    @Schema(description = "Keyed by member_id - one entry per traveller on this Visa service")
    private Map<String, VisaChecklistEntry> visaChecklists;

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

    @Schema(example = "2026-08-13T09:15:22")
    private LocalDateTime createdAt;

    @Schema(example = "2026-08-14T11:02:41")
    private LocalDateTime updatedAt;
}
