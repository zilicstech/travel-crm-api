package com.voyra.crm.dto;

import com.voyra.crm.enums.BookingType;
import com.voyra.crm.enums.PaymentStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Schema(description = "Request body for creating a Booking. Set serviceId to log it on a lead's "
        + "service - type must then match that service's type, and several bookings may be "
        + "logged on one service (a round trip is two Flight bookings). Omit it for a standalone "
        + "walk-in booking created directly from the Bookings tab.")
public class BookingCreateRequest {

    @NotBlank(message = "Client is required")
    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String clientId;

    @Schema(description = "Owner-only: assign the booking to a specific agent. Ignored for the AGENT role (always self).", example = "CB9Y0N")
    private String agentId;

    @Schema(description = "The lead this booking belongs to. Required together with serviceId.", example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String leadId;

    @Schema(description = "The lead's service this booking confirms. type must equal this service's ServiceType.", example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String serviceId;

    @NotNull(message = "Type is required")
    @Schema(example = "FLIGHT")
    private BookingType type;

    @NotBlank(message = "Destination is required")
    @Schema(example = "Dubai, UAE")
    private String destination;

    @Schema(description = "Airline PNR/booking reference", example = "ABCXYZ")
    private String pnr;

    @Schema(description = "E-ticket number", example = "0987654321")
    private String ticketNo;

    @Schema(example = "Emirates")
    private String airline;

    @Schema(description = "Supplier/vendor this was booked through", example = "Cleartrip")
    private String supplier;

    @Schema(description = "Outbound travel date", example = "2026-09-15")
    private LocalDate journeyDate;

    @Schema(description = "Return travel date, if applicable", example = "2026-09-22")
    private LocalDate returnDate;

    @Schema(description = "One-way or round-trip", example = "Round Trip")
    private String tripType;

    @Schema(example = "Held via the supplier's own GDS, ticketed on hold")
    private String notes;

    @Schema(example = "6E 2047")
    private String flightNumber;

    @Schema(example = "Mumbai (BOM)")
    private String flightFrom;

    @Schema(example = "Dubai (DXB)")
    private String flightTo;

    @Schema(example = "Economy")
    private String flightCabin;

    @Schema(example = "HTL-994211")
    private String hotelConfirmationNo;

    @Schema(example = "Atlantis The Palm")
    private String hotelName;

    @Schema(example = "Dubai")
    private String hotelCity;

    @Schema(description = "ISO alpha-2 country code, picked alongside hotelCity", example = "AE")
    private String hotelCountryCode;

    @Schema(example = "2026-09-15")
    private LocalDate hotelCheckIn;

    @Schema(example = "2026-09-20")
    private LocalDate hotelCheckOut;

    @Schema(example = "Deluxe Sea View")
    private String hotelRoomType;

    @Schema(example = "Breakfast Included")
    private String hotelBoardBasis;

    @Schema(example = "2")
    private Integer hotelRooms;

    @Schema(example = "VA-338841")
    private String visaApplicationNo;

    @Schema(example = "UAE")
    private String visaCountry;

    @Schema(example = "2026-08-20")
    private LocalDate visaAppliedDate;

    @Schema(example = "2026-08-28")
    private LocalDate visaAppointmentDate;

    @Schema(example = "2026-09-05")
    private LocalDate visaIssuedDate;

    @Schema(example = "TRF-55291")
    private String transferVoucherNo;

    @Schema(example = "6 Seater (SUV)")
    private String transferVehicleType;

    @Schema(example = "Dubai International Airport")
    private String transferPickup;

    @Schema(example = "Atlantis The Palm")
    private String transferDropoff;

    @Schema(example = "2026-09-15")
    private LocalDate transferDate;

    @Schema(description = "Local clock time, 'HH:mm'", example = "14:30")
    private String transferTime;

    @NotNull(message = "Net cost is required")
    @Schema(description = "Cost paid to the supplier", example = "42000.00")
    private BigDecimal netCost;

    @NotNull(message = "Selling price is required")
    @Schema(description = "Price charged to the customer", example = "52000.00")
    private BigDecimal sellingPrice;

    @Schema(defaultValue = "PENDING", example = "PENDING")
    private PaymentStatus paymentStatus;
}
