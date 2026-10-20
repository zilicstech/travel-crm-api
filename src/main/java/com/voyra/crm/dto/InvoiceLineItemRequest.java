package com.voyra.crm.dto;

import com.voyra.crm.enums.ServiceType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Schema(description = "One line on a draft invoice. Tax fields are not supplied here - TaxEngine computes them from the header's supplyNature and this line's amount on every save.")
public class InvoiceLineItemRequest {

    @NotBlank(message = "Description is required")
    @Schema(example = "Dubai package - 4N/5D, 2 pax")
    private String description;

    @Schema(example = "9985")
    private String sacCode;

    @Schema(example = "PACKAGE")
    private ServiceType serviceType;

    @NotNull(message = "Quantity is required")
    @DecimalMin(value = "0.0", inclusive = false, message = "Quantity must be positive")
    @Schema(example = "1")
    private BigDecimal quantity;

    @NotNull(message = "Unit price is required")
    @DecimalMin(value = "0.0", message = "Unit price cannot be negative")
    @Schema(example = "84000.00")
    private BigDecimal unitPrice;

    @Schema(description = "Flat discount on this line, before tax", example = "0.00")
    private BigDecimal discountAmount;

    @Schema(description = "Display-only split of unitPrice into a base fare and the supplier's own tax component (YQ/YR and similar) - printed as separate Fare/Taxes columns when present. Never fed into TaxEngine.", example = "138996.00")
    private BigDecimal fareAmount;

    @Schema(description = "Display-only - the other half of unitPrice's Fare/Taxes split", example = "0.00")
    private BigDecimal taxAmount;

    @Schema(description = "The booking this line bills. Set by the server on lines it builds from vouchers; on a draft update it only matches a line to keep its edited description.")
    private String bookingId;

    @Schema(description = "The voucher this line bills, when it bills one")
    private String bookingDocumentId;

    @Schema(description = "Set by the server from the voucher: its fare charges in base (basic, yq, ...); ignored when sent")
    private java.util.Map<String, java.math.BigDecimal> charges;

    @Schema(description = "Set by the server from the voucher: the ticket's travellers; ignored when sent")
    private java.util.List<VoucherTraveller> travellers;

    @Schema(description = "Set by the server from the voucher: the ticket's flights; ignored when sent")
    private java.util.List<VoucherLeg> legs;

    @Schema(description = "Set by the server from the voucher: its purchase currency; ignored when sent", example = "AED")
    private String purchaseCurrency;

    @Schema(description = "Set by the server from the voucher: its purchase rate to base; ignored when sent", example = "26.5")
    private java.math.BigDecimal purchaseRate;
}
