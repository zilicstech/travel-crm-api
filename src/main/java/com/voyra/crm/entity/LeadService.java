package com.voyra.crm.entity;

import com.voyra.crm.dto.FlightSectorDto;
import com.voyra.crm.dto.VisaChecklistEntry;
import com.voyra.crm.enums.FlightCabin;
import com.voyra.crm.enums.FlightTripType;
import com.voyra.crm.enums.LeadStatus;
import com.voyra.crm.enums.ServiceStatus;
import com.voyra.crm.enums.ServiceType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * One instance of a service on a lead - a lead may hold any number of Flight/Hotel/Visa/Transfer
 * instances (a round trip and a domestic hop are two Flight services). One wide table for every
 * type rather than four narrow ones, because a lead's service list is read as one set - the
 * Services tab, the cross-lead board - far more often than any single type is read alone.
 *
 * <p>{@code label} is a stored snapshot, not computed on read - see
 * {@link com.voyra.crm.util.ServiceLabelDeriver}. {@code dateFrom}/{@code dateTo} are likewise
 * derived per type on every write - see {@link com.voyra.crm.util.ServiceDateRangeDeriver}.
 *
 * <p>{@code visaChecklists} is keyed by member_id, never by array index, so two Visa services on
 * one lead (two countries) hold two independent checklists per traveller.
 */
@Entity
@Table(name = "lead_service")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class LeadService {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "lead_id", nullable = false, length = 36)
    private String leadId;

    @Column(name = "client_id", nullable = false, length = 36)
    private String clientId;

    /** Denormalized snapshots so the cross-lead Services board is one flat SELECT with no joins. */
    @Column(name = "client_name", nullable = false, length = 150)
    private String clientName;

    @Column(name = "lead_destination", nullable = false, length = 150)
    private String leadDestination;

    @Enumerated(EnumType.STRING)
    @Column(name = "lead_status", nullable = false, length = 20)
    private LeadStatus leadStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private ServiceType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ServiceStatus status;

    @Column(name = "label", nullable = false, length = 200)
    private String label;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    @Column(name = "assigned_agent_id", length = 36)
    private String assignedAgentId;

    @Column(name = "assigned_agent_name", length = 150)
    private String assignedAgentName;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "preferences", columnDefinition = "text[]")
    @Builder.Default
    private List<String> preferences = List.of();

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "date_from")
    private LocalDate dateFrom;

    @Column(name = "date_to")
    private LocalDate dateTo;

    @Column(name = "net_total", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal netTotal = BigDecimal.ZERO;

    @Column(name = "selling_total", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal sellingTotal = BigDecimal.ZERO;

    // ---- Flight ----

    @Enumerated(EnumType.STRING)
    @Column(name = "flight_trip_type", length = 20)
    private FlightTripType flightTripType;

    @Enumerated(EnumType.STRING)
    @Column(name = "flight_cabin", length = 20)
    private FlightCabin flightCabin;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "flight_sectors", columnDefinition = "jsonb")
    @Builder.Default
    private List<FlightSectorDto> flightSectors = List.of();

    // ---- Hotel ----

    @Column(name = "hotel_city", length = 150)
    private String hotelCity;

    @Column(name = "hotel_check_in")
    private LocalDate hotelCheckIn;

    @Column(name = "hotel_check_out")
    private LocalDate hotelCheckOut;

    @Column(name = "hotel_nights")
    private Integer hotelNights;

    @Column(name = "hotel_rooms")
    private Integer hotelRooms;

    // ---- Visa ----

    @Column(name = "visa_source_city", length = 150)
    private String visaSourceCity;

    @Column(name = "visa_source_country", length = 100)
    private String visaSourceCountry;

    @Column(name = "visa_country", length = 100)
    private String visaCountry;

    @Column(name = "visa_intended_travel_date")
    private LocalDate visaIntendedTravelDate;

    @Column(name = "visa_appointment_date")
    private LocalDate visaAppointmentDate;

    /** Keyed by member_id - see the class javadoc. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "visa_checklists", columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, VisaChecklistEntry> visaChecklists = Map.of();

    // ---- Transfer ----

    @Column(name = "transfer_vehicle_type", length = 50)
    private String transferVehicleType;

    @Column(name = "transfer_pickup", length = 150)
    private String transferPickup;

    @Column(name = "transfer_dropoff", length = 150)
    private String transferDropoff;

    @Column(name = "transfer_date")
    private LocalDate transferDate;

    /** Plain 'HH:mm' local clock, never a timestamp. */
    @Column(name = "transfer_time", length = 5)
    private String transferTime;

    @Column(name = "transfer_passengers")
    private Integer transferPassengers;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "created_by", length = 36)
    private String createdBy;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "updated_by", length = 36)
    private String updatedBy;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
