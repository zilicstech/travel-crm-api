package com.voyra.crm.entity;

import com.voyra.crm.enums.MemberRelation;
import com.voyra.crm.enums.MemberType;
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

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A person on a client's roster - the client themself ({@code type = CLIENT}) or anyone they
 * travel with ({@code type = MEMBER}): family, friends, or colleagues on a group booking.
 *
 * <p>There is deliberately no {@code age} column. Airline pax type depends on age at the
 * travel date, not today, so a stored age quietly mis-fares a child who turns 12 between
 * enquiry and departure. Resolve through {@code util.PaxTypeCalculator} instead.
 *
 * <p>Identity fields (dob, gender, passport, nationality) are nullable on purpose. An agent
 * taking a phone enquiry knows a name and little else; the manifest hardens progressively and
 * completeness is checked when a proposal or booking needs it. Requiring them up front just
 * produces "Guest 1" placeholder rows.
 */
@Entity
@Table(name = "member")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class Member {

    @Id
    @Column(name = "member_id", length = 36)
    private String memberId;

    @Column(name = "client_id", nullable = false, length = 36)
    private String clientId;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "email", length = 150)
    private String email;

    @Column(name = "country_code", length = 6)
    private String countryCode;

    @Column(name = "phone", length = 20)
    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 10)
    private MemberType type;

    @Column(name = "dob")
    private LocalDate dob;

    @Column(name = "gender", length = 30)
    private String gender;

    @Enumerated(EnumType.STRING)
    @Column(name = "relation", nullable = false, length = 20)
    private MemberRelation relation;

    @Column(name = "nationality", length = 100)
    private String nationality;

    @Column(name = "passport_number", length = 20)
    private String passportNumber;

    @Column(name = "passport_expiry")
    private LocalDate passportExpiry;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "created_by", length = 36)
    private String createdBy;

    @Column(name = "modified_at")
    private LocalDateTime modifiedAt;

    @Column(name = "modified_by", length = 36)
    private String modifiedBy;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
