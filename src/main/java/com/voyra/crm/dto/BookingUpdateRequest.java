package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Patch semantics - every field optional, only non-null fields are applied. lead/service
 *  linkage and type are never patched - the booking link never changes after creation. */
@Data
@Schema(description = "Request body for updating a Booking's details")
public class BookingUpdateRequest {

    @Size(max = 20, message = "PNR must be 20 characters or fewer")
    @Schema(example = "ABCXYZ")
    private String pnr;

    @Size(max = 50, message = "Ticket number must be 50 characters or fewer")
    @Schema(example = "0987654321")
    private String ticketNo;

    @Size(max = 100, message = "Airline must be 100 characters or fewer")
    @Schema(example = "Emirates")
    private String airline;

    @Size(max = 150, message = "Supplier must be 150 characters or fewer")
    @Schema(example = "Cleartrip")
    private String supplier;

    @Schema(example = "2026-09-15")
    private LocalDate journeyDate;

    @Schema(example = "2026-09-22")
    private LocalDate returnDate;

    @Size(max = 20, message = "Trip type must be 20 characters or fewer")
    @Schema(example = "Round Trip")
    private String tripType;

    @Schema(example = "Held via the supplier's own GDS, ticketed on hold")
    private String notes;

    @Size(max = 20)
    @Schema(example = "6E 2047")
    private String flightNumber;

    @Size(max = 100)
    @Schema(example = "Mumbai (BOM)")
    private String flightFrom;

    @Size(max = 100)
    @Schema(example = "Dubai (DXB)")
    private String flightTo;

    @Size(max = 20)
    @Schema(example = "Economy")
    private String flightCabin;

    @Size(max = 100)
    @Schema(example = "HTL-994211")
    private String hotelConfirmationNo;

    @Size(max = 200)
    @Schema(example = "Atlantis The Palm")
    private String hotelName;

    @Size(max = 150)
    @Schema(example = "Dubai")
    private String hotelCity;

    @Size(max = 2)
    @Schema(description = "ISO alpha-2 country code, picked alongside hotelCity", example = "AE")
    private String hotelCountryCode;

    @Schema(example = "2026-09-15")
    private LocalDate hotelCheckIn;

    @Schema(example = "2026-09-20")
    private LocalDate hotelCheckOut;

    @Size(max = 100)
    @Schema(example = "Deluxe Sea View")
    private String hotelRoomType;

    @Size(max = 50)
    @Schema(example = "Breakfast Included")
    private String hotelBoardBasis;

    @Schema(example = "2")
    private Integer hotelRooms;

    @Size(max = 100)
    @Schema(example = "VA-338841")
    private String visaApplicationNo;

    @Size(max = 100)
    @Schema(example = "UAE")
    private String visaCountry;

    @Schema(example = "2026-08-20")
    private LocalDate visaAppliedDate;

    @Schema(example = "2026-08-28")
    private LocalDate visaAppointmentDate;

    @Schema(example = "2026-09-05")
    private LocalDate visaIssuedDate;

    @Size(max = 100)
    @Schema(example = "TRF-55291")
    private String transferVoucherNo;

    @Size(max = 50)
    @Schema(example = "6 Seater (SUV)")
    private String transferVehicleType;

    @Size(max = 150)
    @Schema(example = "Dubai International Airport")
    private String transferPickup;

    @Size(max = 150)
    @Schema(example = "Atlantis The Palm")
    private String transferDropoff;

    @Schema(example = "2026-09-15")
    private LocalDate transferDate;

    @Size(max = 5)
    @Schema(description = "Local clock time, 'HH:mm'", example = "14:30")
    private String transferTime;

    @DecimalMin(value = "0.00", message = "Net cost cannot be negative")
    @Schema(description = "Cost paid to the supplier", example = "42000.00")
    private BigDecimal netCost;

    @DecimalMin(value = "0.00", message = "Selling price cannot be negative")
    @Schema(description = "Price charged to the customer", example = "52000.00")
    private BigDecimal sellingPrice;

    @Schema(description = "Whether this FLIGHT booking's itinerary leaves India - decides AIR_INTERNATIONAL vs AIR_DOMESTIC on any invoice drafted against it. Ignored for non-FLIGHT bookings.")
    private Boolean internationalTrip;

    @Valid
    @Schema(description = "The whole traveller list - a save with this present replaces every existing passenger (and their sectors) with this set. Omit to leave passengers untouched.")
    private List<BookingPassengerRequest> passengers;
}
