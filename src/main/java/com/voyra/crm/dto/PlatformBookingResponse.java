package com.voyra.crm.dto;

import com.voyra.crm.enums.MirrorStatus;
import com.voyra.crm.enums.PlatformBookingStatus;
import com.voyra.crm.enums.PricingProduct;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** The agency's view of a booking made through Voyra. Hand-built: never carries Voyra's earnings, net or supplier ids; the gross is shown only as the published fare the agency sees in search. */
@Data
@SuperBuilder
@Schema(description = "A booking made through Voyra, as the agency sees it")
public class PlatformBookingResponse {

    @Schema(description = "Booking id", example = "b1d2e3f4-0000-0000-0000-000000000001")
    private String id;

    @Schema(description = "Product booked", example = "FLIGHT")
    private PricingProduct product;

    @Schema(description = "Where the booking stands", example = "TICKETED")
    private PlatformBookingStatus status;

    @Schema(description = "Airline booking reference", example = "ABC123")
    private String pnr;

    @Schema(description = "Ticket numbers once issued")
    private List<String> ticketNumbers;

    @Schema(description = "The airline's published fare, as shown struck through in search", example = "5000.00")
    private BigDecimal publishedFare;

    @Schema(description = "What the wallet was charged", example = "4500.00")
    private BigDecimal displayedFare;

    @Schema(description = "Commission the agency earns after TDS", example = "95.00")
    private BigDecimal agencyEarns;

    @Schema(description = "Flight legs")
    private List<ItinerarySegment> itinerary;

    @Schema(description = "Hotel bookings: the stay; null for flights")
    private HotelStaySummary hotel;

    @Schema(description = "Travellers: name and type only")
    private List<PaxSummary> passengers;

    @Schema(description = "Who in the agency made the booking", example = "Olga Owner")
    private String bookedByName;

    @Schema(description = "CRM client the booking is for, if any", example = "Acme Travel")
    private String clientName;

    @Schema(description = "When the fare was reviewed", example = "2026-10-25T05:00:00Z")
    private Instant createdAt;

    @Schema(description = "When the ticket was issued", example = "2026-10-25T05:02:00Z")
    private Instant ticketedAt;

    @Schema(description = "Amount refunded to the wallet on cancellation", example = "4000.00")
    private BigDecimal cancelRefund;

    @Schema(description = "Latest message from the airline side, if any", example = "Fare sold out")
    private String supplierMessage;

    @Schema(description = "State of the copy written into the agency's CRM bookings", example = "DONE")
    private MirrorStatus mirrorStatus;

    @Schema(description = "CRM booking written for this ticket, once it exists", example = "b2d2e3f4-0000-0000-0000-000000000002")
    private String tenantBookingId;

    @Schema(description = "Plain-language history; filled on the detail view only")
    private List<BookingTimelineLine> timeline;
}
