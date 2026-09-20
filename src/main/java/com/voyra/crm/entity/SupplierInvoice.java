package com.voyra.crm.entity;

import com.voyra.crm.enums.BookingType;
import com.voyra.crm.enums.ItcEligibility;
import com.voyra.crm.enums.ServiceType;
import com.voyra.crm.enums.SupplierInvoiceKind;
import com.voyra.crm.enums.SupplierInvoiceStatus;
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

/**
 * Accounts-payable document - what a vendor billed the agency. A bill's GST is RECORDED, never
 * computed: the supplier has already done their own arithmetic, so {@code SupplierInvoiceService}
 * validates the figures (line sums against the header, CGST+SGST vs IGST against
 * {@code vendor.state_code}) rather than deriving them via {@code TaxEngine} - see
 * ARCHITECTURE-SPINE AD-2. {@link #vendorId}/{@link #vendorName} follow the house
 * id-plus-name-snapshot pattern (AD-3); {@link #vendorId} is nullable only for a legacy row whose
 * original {@code supplier_name} matched no vendor at migration time.
 *
 * <p>Never written to by {@code BookingAccountingSync} or any payables service - booking
 * {@code net_cost}/{@code profit} stay the agent's own estimate (AD-9); this table only carries a
 * {@link #bookingId} for context and report joins.
 */
@Entity
@Table(name = "supplier_invoice")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class SupplierInvoice {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "vendor_id", length = 36)
    private String vendorId;

    @Column(name = "supplier_name", nullable = false, length = 150)
    private String vendorName;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 20)
    @Builder.Default
    private SupplierInvoiceKind kind = SupplierInvoiceKind.PURCHASE;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 20)
    private BookingType category;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private SupplierInvoiceStatus status;

    @Column(name = "supplier_invoice_number", length = 60)
    private String supplierInvoiceNumber;

    @Column(name = "supplier_gstin", length = 20)
    private String supplierGstin;

    @Column(name = "supplier_state_code", length = 2)
    private String supplierStateCode;

    @Column(name = "invoice_date")
    private LocalDate invoiceDate;

    @Column(name = "received_on")
    private LocalDate receivedOn;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "booking_id", length = 36)
    private String bookingId;

    @Column(name = "lead_id", length = 36)
    private String leadId;

    @Enumerated(EnumType.STRING)
    @Column(name = "service_type", length = 20)
    private ServiceType serviceType;

    @Column(name = "reference_note", length = 255)
    private String referenceNote;

    @Column(name = "currency_code", nullable = false, length = 3)
    @Builder.Default
    private String currencyCode = "INR";

    @Column(name = "fx_rate_to_inr", nullable = false, precision = 18, scale = 6)
    @Builder.Default
    private BigDecimal fxRateToInr = BigDecimal.ONE;

    @Column(name = "fx_rate_source", length = 30)
    private String fxRateSource;

    @Column(name = "subtotal", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal subtotal = BigDecimal.ZERO;

    @Column(name = "discount_total", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal discountTotal = BigDecimal.ZERO;

    @Column(name = "taxable_value", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal taxableValue = BigDecimal.ZERO;

    @Column(name = "cgst_amount", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal cgstAmount = BigDecimal.ZERO;

    @Column(name = "sgst_amount", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal sgstAmount = BigDecimal.ZERO;

    @Column(name = "igst_amount", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal igstAmount = BigDecimal.ZERO;

    @Column(name = "gst_total", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal gstTotal = BigDecimal.ZERO;

    @Column(name = "cess_amount", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal cessAmount = BigDecimal.ZERO;

    @Column(name = "round_off", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal roundOff = BigDecimal.ZERO;

    @Column(name = "grand_total", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal grandTotal = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "itc_eligibility", nullable = false, length = 20)
    @Builder.Default
    private ItcEligibility itcEligibility = ItcEligibility.ELIGIBLE;

    @Column(name = "itc_note", length = 255)
    private String itcNote;

    @Column(name = "is_reverse_charge", nullable = false)
    @Builder.Default
    private Boolean isReverseCharge = false;

    @Column(name = "tds_section", length = 20)
    private String tdsSection;

    @Column(name = "tds_rate_percent", nullable = false, precision = 6, scale = 3)
    @Builder.Default
    private BigDecimal tdsRatePercent = BigDecimal.ZERO;

    @Column(name = "tds_amount", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal tdsAmount = BigDecimal.ZERO;

    @Column(name = "taxable_value_inr", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal taxableValueInr = BigDecimal.ZERO;

    @Column(name = "gst_total_inr", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal gstTotalInr = BigDecimal.ZERO;

    @Column(name = "grand_total_inr", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal grandTotalInr = BigDecimal.ZERO;

    @Column(name = "amount_paid", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal amountPaid = BigDecimal.ZERO;

    @Column(name = "credit_note_total", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal creditNoteTotal = BigDecimal.ZERO;

    @Column(name = "balance_due", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal balanceDue = BigDecimal.ZERO;

    @Column(name = "balance_due_inr", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal balanceDueInr = BigDecimal.ZERO;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "approved_by", length = 36)
    private String approvedBy;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Column(name = "cancelled_by", length = 36)
    private String cancelledBy;

    @Column(name = "cancel_reason", length = 255)
    private String cancelReason;

    @Column(name = "notes", length = 1000)
    private String notes;

    @Column(name = "file_key", length = 500)
    private String fileKey;

    @Column(name = "file_name", length = 255)
    private String fileName;

    @Column(name = "content_type", length = 150)
    private String contentType;

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
    }
}
