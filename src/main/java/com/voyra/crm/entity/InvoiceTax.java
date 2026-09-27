package com.voyra.crm.entity;

import com.voyra.crm.enums.TaxKind;
import com.voyra.crm.enums.TaxLineMode;
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
 * One tax the accountant chose to add to an {@link Invoice} - zero, one or several. Unlike the
 * old per-line GST computation, tax here is opt-in at the header: no rows means no tax at all.
 * {@link #taxRateConfigId} is set when the row came from the tax dropdown (provenance only,
 * never re-read to compute amounts - {@link #amount} is what froze at save time, same rule as
 * {@link InvoiceLineItem}); null means the accountant typed a one-off custom tax. {@link
 * #visibleToCustomer} controls only the printed presentation - see {@code InvoicePdfRenderer} -
 * never {@link #amount}, which always counts toward {@code Invoice.grandTotal}.
 */
@Entity
@Table(name = "invoice_tax")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class InvoiceTax {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "invoice_id", nullable = false, length = 36)
    private String invoiceId;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    @Column(name = "label", nullable = false, length = 150)
    private String label;

    @Column(name = "tax_rate_config_id", length = 36)
    private String taxRateConfigId;

    /** Copied from the chosen config at save time; null for a custom/ad-hoc tax - see class doc. */
    @Enumerated(EnumType.STRING)
    @Column(name = "tax_kind", length = 10)
    private TaxKind taxKind;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 10)
    private TaxLineMode mode;

    @Column(name = "rate_percent", precision = 6, scale = 3)
    private BigDecimal ratePercent;

    @Column(name = "flat_amount", precision = 19, scale = 2)
    private BigDecimal flatAmount;

    @Column(name = "cgst_amount", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal cgstAmount = BigDecimal.ZERO;

    @Column(name = "sgst_amount", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal sgstAmount = BigDecimal.ZERO;

    @Column(name = "igst_amount", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal igstAmount = BigDecimal.ZERO;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal amount = BigDecimal.ZERO;

    @Column(name = "visible_to_customer", nullable = false)
    @Builder.Default
    private Boolean visibleToCustomer = true;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
