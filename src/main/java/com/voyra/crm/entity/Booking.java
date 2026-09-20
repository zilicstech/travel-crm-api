package com.voyra.crm.entity;

import com.voyra.crm.enums.BookingStatus;
import com.voyra.crm.enums.BookingType;
import com.voyra.crm.enums.PaymentStatus;
import com.voyra.crm.enums.PaymentStatusSource;
import com.voyra.crm.enums.RefundState;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "booking")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class Booking {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    /** Null for a standalone (walk-in) booking created directly from the Bookings tab. */
    @Column(name = "lead_id", length = 36)
    private String leadId;

    /** Null for a standalone booking. When set, {@link #type} must agree with the owning
     *  service's ServiceType (see ServiceBookingTypeMapper) - the Bookings-tab type filter
     *  and the agent-scoping predicate both depend on that invariant holding. */
    @Column(name = "service_id", length = 36)
    private String serviceId;

    /** Denormalized snapshot of the owning service's type - lets scoping run off this row
     *  alone rather than joining lead_service per booking. Null for a standalone booking. */
    @Enumerated(EnumType.STRING)
    @Column(name = "service_type", length = 20)
    private ServiceType serviceType;

    @Column(name = "service_label", length = 200)
    private String serviceLabel;

    /** Snapshot of lead_service.assigned_agent_id - kept in sync by BookingRepository's
     *  resyncServiceAgent whenever the service is accepted/assigned/reassigned. */
    @Column(name = "service_agent_id", length = 36)
    private String serviceAgentId;

    @Column(name = "service_agent_name", length = 150)
    private String serviceAgentName;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "client_id", nullable = false, length = 36)
    private String clientId;

    @Column(name = "client_name", nullable = false, length = 150)
    private String clientName;

    @Column(name = "agent_id", nullable = false, length = 36)
    private String agentId;

    @Column(name = "agent_name", nullable = false, length = 150)
    private String agentName;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private BookingType type;

    @Column(name = "destination", nullable = false, length = 150)
    private String destination;

    @Column(name = "pnr", length = 20)
    private String pnr;

    @Column(name = "ticket_no", length = 50)
    private String ticketNo;

    @Column(name = "airline", length = 100)
    private String airline;

    @Column(name = "supplier", length = 150)
    private String supplier;

    @Column(name = "journey_date")
    private LocalDate journeyDate;

    @Column(name = "return_date")
    private LocalDate returnDate;

    @Column(name = "trip_type", length = 20)
    private String tripType;

    @Column(name = "flight_number", length = 20)
    private String flightNumber;

    @Column(name = "flight_from", length = 100)
    private String flightFrom;

    @Column(name = "flight_to", length = 100)
    private String flightTo;

    @Column(name = "flight_cabin", length = 20)
    private String flightCabin;

    @Column(name = "hotel_confirmation_no", length = 100)
    private String hotelConfirmationNo;

    @Column(name = "hotel_name", length = 200)
    private String hotelName;

    @Column(name = "hotel_city", length = 150)
    private String hotelCity;

    @Column(name = "hotel_check_in")
    private LocalDate hotelCheckIn;

    @Column(name = "hotel_check_out")
    private LocalDate hotelCheckOut;

    @Column(name = "hotel_room_type", length = 100)
    private String hotelRoomType;

    @Column(name = "hotel_board_basis", length = 50)
    private String hotelBoardBasis;

    @Column(name = "hotel_rooms")
    private Integer hotelRooms;

    @Column(name = "visa_application_no", length = 100)
    private String visaApplicationNo;

    @Column(name = "visa_country", length = 100)
    private String visaCountry;

    @Column(name = "visa_applied_date")
    private LocalDate visaAppliedDate;

    @Column(name = "visa_appointment_date")
    private LocalDate visaAppointmentDate;

    @Column(name = "visa_issued_date")
    private LocalDate visaIssuedDate;

    @Column(name = "transfer_voucher_no", length = 100)
    private String transferVoucherNo;

    @Column(name = "transfer_vehicle_type", length = 50)
    private String transferVehicleType;

    @Column(name = "transfer_pickup", length = 150)
    private String transferPickup;

    @Column(name = "transfer_dropoff", length = 150)
    private String transferDropoff;

    @Column(name = "transfer_date")
    private LocalDate transferDate;

    /** Plain 'HH:mm' local clock string, never a timestamp - same convention as
     *  lead_service's own time fields. */
    @Column(name = "transfer_time", length = 5)
    private String transferTime;

    @Column(name = "net_cost", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal netCost = BigDecimal.ZERO;

    @Column(name = "selling_price", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal sellingPrice = BigDecimal.ZERO;

    @Column(name = "profit", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal profit = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "booking_status", nullable = false, length = 20)
    private BookingStatus bookingStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false, length = 20)
    private PaymentStatus paymentStatus;

    @Column(name = "booking_date")
    private LocalDate bookingDate;

    @Column(name = "cancel_reason", length = 255)
    private String cancelReason;

    /** @deprecated superseded by {@link #refundState}; kept read-only for one release. */
    @Deprecated
    @Column(name = "refund_status", length = 100)
    private String refundStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "refund_state", nullable = false, length = 20)
    @Builder.Default
    private RefundState refundState = RefundState.NOT_APPLICABLE;

    @Column(name = "refund_amount", precision = 19, scale = 2)
    private BigDecimal refundAmount;

    @Column(name = "refund_due_date")
    private LocalDate refundDueDate;

    @Column(name = "refunded_at")
    private LocalDateTime refundedAt;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Column(name = "cancelled_by", length = 36)
    private String cancelledBy;

    @Column(name = "ticketing_deadline")
    private LocalDate ticketingDeadline;

    @Column(name = "cancellation_deadline")
    private LocalDate cancellationDeadline;

    @Column(name = "deadline_note", length = 255)
    private String deadlineNote;

    @Column(name = "created_date")
    private LocalDateTime createdDate;

    @Column(name = "created_by", length = 36)
    private String createdBy;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "updated_by", length = 36)
    private String updatedBy;

    @Column(name = "primary_invoice_id", length = 36)
    private String primaryInvoiceId;

    @Column(name = "invoiced_total_inr", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal invoicedTotalInr = BigDecimal.ZERO;

    @Column(name = "received_total_inr", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal receivedTotalInr = BigDecimal.ZERO;

    @Column(name = "refunded_total_inr", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal refundedTotalInr = BigDecimal.ZERO;

    /** MANUAL until a tax invoice is issued against this booking, then DERIVED forever - see {@code BookingAccountingSync}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status_source", nullable = false, length = 20)
    @Builder.Default
    private PaymentStatusSource paymentStatusSource = PaymentStatusSource.MANUAL;

    @PrePersist
    protected void onCreate() {
        if (createdDate == null) {
            createdDate = LocalDateTime.now();
        }
        if (bookingDate == null) {
            bookingDate = LocalDate.now();
        }
    }
}
