package com.voyra.crm.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "customer_interaction")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class CustomerInteraction {

    @Id
    @Column(name = "id", length = 6)
    private String id;

    @Column(name = "customer_id", nullable = false, length = 6)
    private String customerId;

    @Column(name = "author_agent_id", nullable = false, length = 6)
    private String authorAgentId;

    @Column(name = "author_name", nullable = false, length = 150)
    private String authorName;

    @Column(name = "note", nullable = false)
    private String note;

    @Column(name = "created_date")
    private LocalDateTime createdDate;

    @PrePersist
    protected void onCreate() {
        if (createdDate == null) {
            createdDate = LocalDateTime.now();
        }
    }
}
