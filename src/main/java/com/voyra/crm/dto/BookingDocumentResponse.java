package com.voyra.crm.dto;

import com.voyra.crm.enums.BookingDocumentType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "One voucher document attached to a booking")
public class BookingDocumentResponse {

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String id;

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String bookingId;

    @Schema(example = "E_TICKET")
    private BookingDocumentType docType;

    @Schema(example = "0987654321")
    private String referenceNumber;

    @Schema(example = "2026-09-20")
    private LocalDate issuedDate;

    @Schema(description = "Whether a file has been attached", example = "true")
    private boolean hasFile;

    @Schema(description = "Original filename of the attached file, if any", example = "emirates-eticket.pdf")
    private String fileName;

    @Schema(example = "Reissued after the airline's schedule change")
    private String notes;

    @Schema(example = "2026-08-13T09:15:22")
    private LocalDateTime createdAt;

    @Schema(description = "What this voucher added to the booking's net cost", example = "4000.00")
    private java.math.BigDecimal netCost;

    @Schema(description = "What this voucher added to the booking's selling price", example = "4400.00")
    private java.math.BigDecimal sellingPrice;

    @Schema(example = "PERCENT")
    private com.voyra.crm.enums.MarkupMode markupMode;

    @Schema(example = "10.00")
    private java.math.BigDecimal markupValue;

    @Schema(description = "Currency the purchase price was typed in; null when typed in base", example = "AED")
    private String purchaseCurrency;

    @Schema(description = "The purchase price as typed, in purchaseCurrency", example = "1000.00")
    private java.math.BigDecimal purchasePrice;

    @Schema(description = "1 unit of purchaseCurrency in base, as entered", example = "22.85")
    private java.math.BigDecimal purchaseRate;

    @Schema(description = "Currency a FLAT markup was typed in", example = "AED")
    private String markupCurrency;

    @Schema(description = "The FLAT markup as typed, in markupCurrency", example = "100.00")
    private java.math.BigDecimal markupInput;

    @Schema(description = "1 unit of markupCurrency in base, as entered", example = "22.85")
    private java.math.BigDecimal markupRate;

    @Schema(description = "The supplier (Vendor) of this voucher, when one was picked")
    private String vendorId;

    @Schema(description = "The supplier bill this voucher drafted, if any", example = "si_7f3a")
    private String supplierInvoiceId;

    @Schema(description = "The fare's charges in the purchase currency (basic, yq, yr, k3, oc, otherTax, processing, otherCharges); null when typed as one price")
    private java.util.Map<String, java.math.BigDecimal> charges;

    @Schema(description = "PERCENT of the basic fare or FLAT; null when no commission", example = "PERCENT")
    private com.voyra.crm.enums.MarkupMode commissionMode;

    @Schema(description = "The commission % or amount as typed", example = "5.00")
    private java.math.BigDecimal commissionValue;

    @Schema(description = "TDS % the supplier deducts on the commission", example = "2.00")
    private java.math.BigDecimal supplierTdsRate;

    @Schema(description = "The commission the supplier pays, in the purchase currency", example = "410.00")
    private java.math.BigDecimal commission;

    @Schema(description = "The TDS the supplier deducts on it, in the purchase currency", example = "8.20")
    private java.math.BigDecimal supplierTds;

    @Schema(description = "The commission in base", example = "410.00")
    private java.math.BigDecimal commissionInr;

    @Schema(description = "This voucher's travel details, flat keys by booking type")
    private java.util.Map<String, String> details;

    @Schema(description = "An air ticket's travellers, in order")
    private java.util.List<VoucherTraveller> travellers;

    @Schema(description = "An air ticket's flights, in order")
    private java.util.List<VoucherLeg> legs;

    @Schema(description = "True once an issued invoice covers this voucher - it can no longer be edited or removed")
    private Boolean invoiced;

    @Schema(description = "True once this voucher's supplier bill is approved - its cost, markup and supplier are locked and it can't be removed; text fields still edit")
    private Boolean billApproved;

    @Schema(description = "A live draft invoice bills this voucher: it stays as it is until that draft is generated or deleted")
    private Boolean onDraft;
}
