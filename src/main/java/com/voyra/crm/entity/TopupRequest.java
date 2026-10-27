package com.voyra.crm.entity;

import com.voyra.crm.enums.TopupMode;
import com.voyra.crm.enums.TopupStatus;
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
import java.time.Instant;

/** An agency's request to add money to its wallet, by gateway or bank transfer (public schema). */
@Entity
@Table(name = "topup_request")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class TopupRequest {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "wallet_id", nullable = false, length = 36)
    private String walletId;

    @Column(name = "tenant_id", nullable = false, length = 36)
    private String tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 14)
    private TopupMode mode;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "approved_amount", precision = 19, scale = 2)
    private BigDecimal approvedAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private TopupStatus status;

    @Column(name = "bank_ref", length = 40)
    private String bankRef;

    @Column(name = "bank_ref_normalized", length = 40)
    private String bankRefNormalized;

    @Column(name = "proof_file_key", length = 500)
    private String proofFileKey;

    @Column(name = "proof_file_name", length = 255)
    private String proofFileName;

    @Column(name = "proof_content_type", length = 100)
    private String proofContentType;

    @Column(name = "gateway_order_id", length = 60)
    private String gatewayOrderId;

    @Column(name = "gateway_payment_id", length = 60)
    private String gatewayPaymentId;

    @Column(name = "note", length = 500)
    private String note;

    @Column(name = "review_note", length = 1000)
    private String reviewNote;

    @Column(name = "requested_by", length = 36)
    private String requestedBy;

    @Column(name = "requested_by_name", length = 150)
    private String requestedByName;

    @Column(name = "reviewed_by", length = 36)
    private String reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "credit_entry_id", length = 36)
    private String creditEntryId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
