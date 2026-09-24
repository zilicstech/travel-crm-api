package com.voyra.crm.entity;

import com.voyra.crm.enums.PaxType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One traveller on one {@link Booking}, carrying the fare attributed to them. Flat FK to the
 * booking (blueprint §8.4), never a JPA association - loaded via
 * {@code BookingPassengerRepository.findByBookingIdOrderBySortOrderAsc}.
 *
 * <p>{@link #leadMemberId} points at the traveller's row on the owning lead's manifest
 * ({@link LeadMember}) when the booking came from a lead; {@link #passengerName} is a snapshot
 * taken at add-time so invoice printing needs no join and survives the traveller later being
 * renamed or dropped from the lead. Its {@link #fareAmount} is what
 * {@code InvoiceDocumentService} turns into that passenger's invoice line - see
 * {@code ACCOUNTING_REDESIGN_SPEC.md} §5.1 and gap 1.
 */
@Entity
@Table(name = "booking_passenger")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class BookingPassenger {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "booking_id", nullable = false, length = 36)
    private String bookingId;

    @Column(name = "lead_member_id", length = 36)
    private String leadMemberId;

    @Column(name = "passenger_name", nullable = false, length = 150)
    private String passengerName;

    @Enumerated(EnumType.STRING)
    @Column(name = "pax_type", length = 10)
    private PaxType paxType;

    @Column(name = "fare_amount", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal fareAmount = BigDecimal.ZERO;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "created_by", length = 36)
    private String createdBy;
}
