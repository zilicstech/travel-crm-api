package com.voyra.crm.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One itinerary leg under one {@link BookingPassenger}. Flat FK, never a JPA association -
 * loaded via {@code BookingSectorRepository.findByBookingPassengerIdOrderBySortOrderAsc}.
 *
 * <p>Only populated for sector-wise categories - see
 * {@code InvoiceServiceCategory.isSectorWise()}; a hotel or visa passenger has none. The fare
 * is never repeated here - it lives once on the parent {@link BookingPassenger}, matching the
 * source system stating a fare once per passenger block regardless of leg count (see
 * {@code ACCOUNTING_REDESIGN_SPEC.md} §2.4).
 */
@Entity
@Table(name = "booking_sector")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class BookingSector {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "booking_passenger_id", nullable = false, length = 36)
    private String bookingPassengerId;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    @Column(name = "sector_from", length = 20)
    private String sectorFrom;

    @Column(name = "sector_to", length = 20)
    private String sectorTo;

    @Column(name = "flight_number", length = 20)
    private String flightNumber;

    @Column(name = "travel_date")
    private LocalDate travelDate;

    @Column(name = "cabin_class", length = 20)
    private String cabinClass;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
