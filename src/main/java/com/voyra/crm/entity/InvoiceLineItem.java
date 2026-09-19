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
import java.time.LocalDateTime;

/**
 * One line on an {@link Invoice}. Flat FK column to the header, never a JPA association
 * (blueprint §8.4) - loaded via {@code InvoiceLineItemRepository.findByInvoiceIdOrderBySortOrder}.
 * {@link #taxRateConfigId} is provenance only, never re-read to compute amounts - every rate and
 * amount here is what {@code TaxEngine} returned when this line was last saved, frozen at issue.
 */
@Entity
@Table(name = "invoice_line_item")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class InvoiceLineItem {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "invoice_id", nullable = false, length = 36)
    private String invoiceId;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    @Column(name = "description", nullable = false, length = 500)
    private String description;

    @Column(name = "sac_code", length = 10)
    private String sacCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "service_type", length = 30)
    private ServiceType serviceType;

    @Column(name = "quantity", nullable = false, precision = 12, scale = 3)
    @Builder.Default
    private BigDecimal quantity = BigDecimal.ONE;

    @Column(name = "unit_price", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal unitPrice = BigDecimal.ZERO;

    @Column(name = "line_subtotal", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal lineSubtotal = BigDecimal.ZERO;

    @Column(name = "discount_amount", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal discountAmount = BigDecimal.ZERO;

    @Column(name = "taxable_percent", nullable = false, precision = 6, scale = 3)
    @Builder.Default
    private BigDecimal taxablePercent = new BigDecimal("100.000");

    @Column(name = "taxable_value", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal taxableValue = BigDecimal.ZERO;

    @Column(name = "gst_rate_percent", nullable = false, precision = 6, scale = 3)
    @Builder.Default
    private BigDecimal gstRatePercent = BigDecimal.ZERO;

    @Column(name = "cgst_rate_percent", nullable = false, precision = 6, scale = 3)
    @Builder.Default
    private BigDecimal cgstRatePercent = BigDecimal.ZERO;

    @Column(name = "sgst_rate_percent", nullable = false, precision = 6, scale = 3)
    @Builder.Default
    private BigDecimal sgstRatePercent = BigDecimal.ZERO;

    @Column(name = "igst_rate_percent", nullable = false, precision = 6, scale = 3)
    @Builder.Default
    private BigDecimal igstRatePercent = BigDecimal.ZERO;

    @Column(name = "cgst_amount", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal cgstAmount = BigDecimal.ZERO;

    @Column(name = "sgst_amount", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal sgstAmount = BigDecimal.ZERO;

    @Column(name = "igst_amount", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal igstAmount = BigDecimal.ZERO;

    @Column(name = "line_total", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal lineTotal = BigDecimal.ZERO;

    @Column(name = "tax_rate_config_id", length = 36)
    private String taxRateConfigId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
