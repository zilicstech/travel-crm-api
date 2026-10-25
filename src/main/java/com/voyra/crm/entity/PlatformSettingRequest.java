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

import java.time.Instant;

/** A platform setting change one admin requested and a different admin must approve - public schema. */
@Entity
@Table(name = "platform_setting_request")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class PlatformSettingRequest {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "setting_key", nullable = false, length = 60)
    private String settingKey;

    @Column(name = "value", nullable = false, length = 4000)
    private String value;

    @Column(name = "requested_by", nullable = false, length = 36)
    private String requestedBy;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "decided_by", length = 36)
    private String decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    /** APPROVED or WITHDRAWN once decided; null while open. */
    @Column(name = "outcome", length = 20)
    private String outcome;
}
