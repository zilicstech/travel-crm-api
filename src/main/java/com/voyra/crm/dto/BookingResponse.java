package com.voyra.crm.dto;

import com.voyra.crm.enums.BookingStatus;
import com.voyra.crm.enums.BookingType;
import com.voyra.crm.enums.PaymentStatus;
import com.voyra.crm.enums.PaymentStatusSource;
import com.voyra.crm.enums.RefundState;
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

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A Booking, with profit always server-computed")
public class BookingResponse {

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String id;

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String clientId;

    @Schema(description = "Denormalized snapshot, live-synced on client rename", example = "Jane Doe")
    private String clientName;

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String agentId;

    @Schema(description = "Denormalized snapshot, live-synced on agent rename", example = "Liam Smith")
    private String agentName;

    @Schema(description = "Null for a standalone booking created directly from the Bookings tab", example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String leadId;

    @Schema(description = "The lead's service this booking confirms. Null for a standalone booking.", example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String serviceId;

    @Schema(description = "Denormalized snapshot of the owning service's type", example = "FLIGHT")
    private ServiceType serviceType;

    @Schema(description = "Denormalized snapshot of the owning service's label", example = "Flight — BOM–DXB")
    private String serviceLabel;

    @Schema(description = "Denormalized snapshot of the owning service's assigned agent - who this booking is visible to besides its own agentId", example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String serviceAgentId;

    @Schema(example = "Liam Smith")
    private String serviceAgentName;

    @Schema(example = "FLIGHT")
    private BookingType type;

    @Schema(example = "Dubai, UAE")
    private String destination;

    @Schema(example = "ABCXYZ")
    private String pnr;

    @Schema(example = "0987654321")
    private String ticketNo;

    @Schema(example = "Emirates")
    private String airline;

    @Schema(example = "Cleartrip")
    private String supplier;

    @Schema(example = "2026-09-15")
    private LocalDate journeyDate;

    @Schema(example = "2026-09-22")
    private LocalDate returnDate;

    @Schema(example = "Round Trip")
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

    @Schema(description = "Cost paid to the supplier", example = "42000.00")
    private BigDecimal netCost;

    @Schema(description = "Price charged to the customer", example = "52000.00")
    private BigDecimal sellingPrice;

    @Schema(description = "Always sellingPrice minus netCost, recomputed server-side", example = "10000.00")
    private BigDecimal profit;

    @Schema(example = "CONFIRMED")
    private BookingStatus bookingStatus;

    @Schema(example = "PAID")
    private PaymentStatus paymentStatus;

    @Schema(example = "2026-08-13")
    private LocalDate bookingDate;

    @Schema(description = "Required when bookingStatus is CANCELLED")
    private String cancelReason;

    @Schema(description = "Deprecated - superseded by refundState", example = "Refunded ₹62,000")
    private String refundStatus;

    @Schema(example = "REFUND_PENDING")
    private RefundState refundState;

    @Schema(example = "42000.00")
    private BigDecimal refundAmount;

    @Schema(example = "2026-10-01")
    private LocalDate refundDueDate;

    @Schema(example = "2026-09-25T11:00:00")
    private LocalDateTime refundedAt;

    @Schema(example = "2026-09-10T16:30:00")
    private LocalDateTime cancelledAt;

    @Schema(description = "Ticketing time limit", example = "2026-09-18")
    private LocalDate ticketingDeadline;

    @Schema(description = "Last date to cancel without penalty", example = "2026-09-15")
    private LocalDate cancellationDeadline;

    @Schema(example = "Fare holds ticket only until 6 PM IST")
    private String deadlineNote;

    @Schema(example = "2026-08-13T09:15:22")
    private LocalDateTime createdDate;

    @Schema(description = "The live tax invoice against this booking, if any", example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String primaryInvoiceId;

    @Schema(description = "Sum of the live (non-cancelled) tax invoice's grandTotalInr for this booking", example = "280900.00")
    private BigDecimal invoicedTotalInr;

    @Schema(description = "Sum of RECEIPT-direction receipts against this booking, in INR", example = "50000.00")
    private BigDecimal receivedTotalInr;

    @Schema(description = "Sum of REFUND-direction receipts against this booking, in INR", example = "0.00")
    private BigDecimal refundedTotalInr;

    @Schema(description = "MANUAL until a tax invoice is issued against this booking, then DERIVED forever", example = "MANUAL")
    private PaymentStatusSource paymentStatusSource;

    @Schema(description = "Voucher documents attached to this booking - e-ticket, hotel voucher, insurance")
    private List<BookingDocumentResponse> documents;

    @Schema(description = "Whether this FLIGHT booking's itinerary leaves India")
    private Boolean internationalTrip;

    @Schema(description = "Travellers on this booking, each with the fare attributed to them and their itinerary legs")
    private List<BookingPassengerResponse> passengers;
}
