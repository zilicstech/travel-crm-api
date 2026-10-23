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

/** A platform-wide setting - public schema, natural key. */
@Entity
@Table(name = "platform_setting")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class PlatformSetting {

    @Id
    @Column(name = "key", length = 60)
    private String key;

    @Column(name = "value", nullable = false, length = 4000)
    private String value;

    @Column(name = "updated_by", length = 36)
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
