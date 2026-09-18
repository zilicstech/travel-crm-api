package com.voyra.crm.entity;

import com.voyra.crm.enums.InvoiceStatus;
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
@Table(name = "client_invoice")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class ClientInvoice {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "client_id", nullable = false, length = 36)
    private String clientId;

    @Column(name = "client_name", nullable = false, length = 150)
    private String clientName;

    @Column(name = "agent_id", nullable = false, length = 36)
    private String agentId;

    /** Null means a direct client invoice outside any lead - unchanged pre-existing behaviour. */
    @Column(name = "lead_id", length = 36)
    private String leadId;

    @Column(name = "service_id", length = 36)
    private String serviceId;

    @Column(name = "service_label", length = 200)
    private String serviceLabel;

    @Column(name = "description", length = 255)
    private String description;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal amount = BigDecimal.ZERO;

    @Column(name = "gst", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal gst = BigDecimal.ZERO;

    @Column(name = "total_with_gst", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal totalWithGst = BigDecimal.ZERO;

    @Column(name = "amount_paid", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal amountPaid = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private InvoiceStatus status;

    @Column(name = "invoice_date")
    private LocalDate invoiceDate;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "payment_mode", length = 50)
    private String paymentMode;

    @Column(name = "created_date")
    private LocalDateTime createdDate;

    @Column(name = "created_by", length = 36)
    private String createdBy;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "updated_by", length = 36)
    private String updatedBy;

    @PrePersist
    protected void onCreate() {
        if (createdDate == null) {
            createdDate = LocalDateTime.now();
        }
        if (invoiceDate == null) {
            invoiceDate = LocalDate.now();
        }
    }
}
