package com.voyra.crm.entity;

import com.voyra.crm.enums.ServiceType;
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
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One supplier's share of what a booking costs - a DMC, an airline, a hotel - so that a single
 * booking can raise one supplier bill per vendor instead of one blended bill. Flat FK to the
 * booking (blueprint §8.4), never a JPA association. {@link #vendorName} is a live-synced
 * snapshot, same pattern as {@code SupplierInvoice.vendorName}. {@link #bookingPassengerId} is
 * nullable - a component may belong to one traveller or to the booking as a whole.
 *
 * <p>{@code BookingService} keeps {@code Booking.netCost} as the live-synced sum of
 * {@link #netCostInr} across a booking's components whenever any exist - see
 * {@code ACCOUNTING_EXPANSION_ARCHITECTURE.md} Decision 7, Rule 7.3. A booking with zero
 * components here is unaffected; {@code Booking.netCost} keeps meaning exactly what it always did.
 */
@Entity
@Table(name = "booking_cost_component")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class BookingCostComponent {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "booking_id", nullable = false, length = 36)
    private String bookingId;

    @Column(name = "booking_passenger_id", length = 36)
    private String bookingPassengerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "service_type", nullable = false, length = 20)
    private ServiceType serviceType;

    @Column(name = "vendor_id", length = 36)
    private String vendorId;

    @Column(name = "vendor_name", length = 120)
    private String vendorName;

    @Column(name = "description", length = 200)
    private String description;

    @Column(name = "net_cost", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal netCost = BigDecimal.ZERO;

    @Column(name = "currency_code", nullable = false, length = 3)
    @Builder.Default
    private String currencyCode = "INR";

    @Column(name = "fx_rate_to_inr", nullable = false, precision = 18, scale = 6)
    @Builder.Default
    private BigDecimal fxRateToInr = BigDecimal.ONE;

    @Column(name = "net_cost_inr", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal netCostInr = BigDecimal.ZERO;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "created_by", length = 36)
    private String createdBy;
}
