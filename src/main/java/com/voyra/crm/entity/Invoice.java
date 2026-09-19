package com.voyra.crm.entity;

import com.voyra.crm.enums.FxRateSource;
import com.voyra.crm.enums.InvoiceDocumentType;
import com.voyra.crm.enums.InvoiceLifecycle;
import com.voyra.crm.enums.SupplyNature;
import com.voyra.crm.enums.TaxTreatment;
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
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One booking, one tax invoice. {@link #invoiceNumber} stays null while {@link #status} is
 * DRAFT - it is allocated only at the DRAFT -&gt; ISSUED transition, which is what keeps the
 * numbering series gap-free (see {@code service.DocumentNumberService}). Recipient and supplier
 * fields are a point-in-time snapshot taken from {@code Client}/{@code Tenant} at issue, not a
 * live join, so a reprint years later never depends on today's row. Every tax figure here is
 * exactly what was computed and frozen at issue - see {@code service.TaxEngine} - and is never
 * recomputed once {@link #status} leaves DRAFT.
 */
@Entity
@Table(name = "invoice")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class Invoice {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "invoice_number", length = 40)
    private String invoiceNumber;

    @Column(name = "financial_year", length = 9)
    private String financialYear;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false, length = 20)
    private InvoiceDocumentType documentType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private InvoiceLifecycle status;

    @Column(name = "client_id", nullable = false, length = 36)
    private String clientId;

    @Column(name = "client_name", nullable = false, length = 150)
    private String clientName;

    @Column(name = "client_gstin", length = 20)
    private String clientGstin;

    @Column(name = "client_state_code", length = 2)
    private String clientStateCode;

    @Column(name = "billing_address", length = 500)
    private String billingAddress;

    @Column(name = "agency_legal_name", length = 200)
    private String agencyLegalName;

    @Column(name = "agency_gstin", length = 20)
    private String agencyGstin;

    @Column(name = "agency_state_code", length = 2)
    private String agencyStateCode;

    @Column(name = "agency_address", length = 500)
    private String agencyAddress;

    @Column(name = "booking_id", length = 36)
    private String bookingId;

    @Column(name = "lead_id", length = 36)
    private String leadId;

    @Column(name = "agent_id", nullable = false, length = 36)
    private String agentId;

    @Column(name = "place_of_supply_code", nullable = false, length = 2)
    private String placeOfSupplyCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "supply_nature", nullable = false, length = 30)
    private SupplyNature supplyNature;

    @Enumerated(EnumType.STRING)
    @Column(name = "tax_treatment", nullable = false, length = 20)
    private TaxTreatment taxTreatment;

    /** Accountant's own override input - re-supplied to TaxEngine on every draft recompute. Distinct from the resolved {@link #placeOfSupplyCode} above. */
    @Column(name = "place_of_supply_override", length = 2)
    private String placeOfSupplyOverride;

    @Column(name = "export_of_service_requested", nullable = false)
    @Builder.Default
    private Boolean exportOfServiceRequested = false;

    @Column(name = "currency_code", nullable = false, length = 3)
    @Builder.Default
    private String currencyCode = "INR";

    @Column(name = "fx_rate_to_inr", nullable = false, precision = 18, scale = 6)
    @Builder.Default
    private BigDecimal fxRateToInr = BigDecimal.ONE;

    @Enumerated(EnumType.STRING)
    @Column(name = "fx_rate_source", length = 30)
    private FxRateSource fxRateSource;

    @Column(name = "fx_locked_at")
    private LocalDateTime fxLockedAt;

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

    @Column(name = "tcs_rate_percent", nullable = false, precision = 6, scale = 3)
    @Builder.Default
    private BigDecimal tcsRatePercent = BigDecimal.ZERO;

    @Column(name = "tcs_section", length = 20)
    private String tcsSection;

    @Column(name = "tcs_base_amount", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal tcsBaseAmount = BigDecimal.ZERO;

    @Column(name = "tcs_amount", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal tcsAmount = BigDecimal.ZERO;

    @Column(name = "round_off", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal roundOff = BigDecimal.ZERO;

    @Column(name = "grand_total", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal grandTotal = BigDecimal.ZERO;

    @Column(name = "taxable_value_inr", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal taxableValueInr = BigDecimal.ZERO;

    @Column(name = "gst_total_inr", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal gstTotalInr = BigDecimal.ZERO;

    @Column(name = "tcs_amount_inr", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal tcsAmountInr = BigDecimal.ZERO;

    @Column(name = "grand_total_inr", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal grandTotalInr = BigDecimal.ZERO;

    @Column(name = "amount_received", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal amountReceived = BigDecimal.ZERO;

    @Column(name = "credit_note_total", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal creditNoteTotal = BigDecimal.ZERO;

    @Column(name = "balance_due", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal balanceDue = BigDecimal.ZERO;

    @Column(name = "balance_due_inr", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal balanceDueInr = BigDecimal.ZERO;

    @Column(name = "invoice_date")
    private LocalDate invoiceDate;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "issued_at")
    private LocalDateTime issuedAt;

    @Column(name = "issued_by", length = 36)
    private String issuedBy;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Column(name = "cancelled_by", length = 36)
    private String cancelledBy;

    @Column(name = "cancel_reason", length = 255)
    private String cancelReason;

    @Column(name = "supersedes_invoice_id", length = 36)
    private String supersedesInvoiceId;

    @Column(name = "notes", length = 1000)
    private String notes;

    @Column(name = "terms", length = 2000)
    private String terms;

    @Column(name = "pdf_file_key", length = 500)
    private String pdfFileKey;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "created_by", length = 36)
    private String createdBy;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "updated_by", length = 36)
    private String updatedBy;
}
