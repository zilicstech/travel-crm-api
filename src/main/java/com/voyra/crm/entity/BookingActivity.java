package com.voyra.crm.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** One thing someone did on a booking, shown to everyone who may open it. Append-only. */
@Entity
@Table(name = "booking_activity")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BookingActivity {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "booking_id", nullable = false, length = 36)
    private String bookingId;

    @Column(name = "kind", nullable = false, length = 40)
    private String kind;

    @Column(name = "text", nullable = false, length = 500)
    private String text;

    @Column(name = "by_id", nullable = false, length = 36)
    private String byId;

    @Column(name = "by_name", nullable = false, length = 150)
    private String byName;

    @Column(name = "at", nullable = false)
    private LocalDateTime at;
}
