package com.voyra.crm.entity;

import com.voyra.crm.enums.BookingStatus;
import com.voyra.crm.enums.BookingType;
import com.voyra.crm.enums.PaymentStatus;
import com.voyra.crm.enums.PaymentStatusSource;
import com.voyra.crm.enums.RefundState;
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
