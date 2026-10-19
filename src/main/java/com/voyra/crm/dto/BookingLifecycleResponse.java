package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A booking's lifecycle: each step's state, the money behind it, and what the caller may do next")
public class BookingLifecycleResponse {

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String bookingId;

    @Schema(description = "The lifecycle steps in order: CREATED, VOUCHER, INVOICE, PAYMENT, then CANCELLED, CLIENT_REFUND, SUPPLIER_REFUND once cancelled")
    private List<Step> steps;

    @Schema(description = "Actions this caller may take now - the UI shows a button only for these",
            example = "[\"GENERATE_INVOICE\",\"RECORD_PAYMENT\"]")
    private List<String> allowedActions;

    @Schema(description = "Invoices, receipts, supplier bills and supplier payments for this booking, oldest first. Supplier rows are omitted for agents.")
    private List<MoneyRecord> records;

    @Schema(description = "Still to collect from the client", example = "12000.00")
    private BigDecimal toCollectInr;

    @Schema(description = "Price not yet on an issued invoice, before GST - what Generate invoice bills next (0 on a cancelled booking)", example = "2500.00")
    private BigDecimal uninvoicedInr;

    @Schema(description = "Money on the client's account this booking's invoice may draw: its own advances plus any not reserved for another booking", example = "5000.00")
    private BigDecimal clientWalletInr;

    @Schema(description = "Still to come back from the supplier on a cancelled booking", example = "6000.00")
    private BigDecimal supplierRefundOutstandingInr;

    @Schema(description = "A supplier refund the agent recorded as received in the agency bank, waiting for Accounts to find it on the statement. Null when none is open.")
    private SupplierRefundClaim supplierRefundClaim;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SupplierRefundClaim {
        @Schema(example = "6000.00")
        private BigDecimal amountInr;
        @Schema(example = "2026-10-18")
        private LocalDate receivedOn;
        @Schema(description = "UTR or similar the agent saw", example = "UTR123456")
        private String reference;
        @Schema(example = "Vikram Rao")
        private String claimedBy;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Step {
        @Schema(example = "INVOICE")
        private String key;
        @Schema(description = "DONE, PARTIAL, OPEN, WAITING or NA", example = "OPEN")
        private String state;
        @Schema(example = "Not invoiced yet")
        private String summary;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MoneyRecord {
        @Schema(description = "Id of the invoice, receipt, bill or payment")
        private String id;
        @Schema(description = "INVOICE, CREDIT_NOTE, RECEIPT, ADVANCE, ADVANCE_APPLIED, REFUND, SUPPLIER_BILL, SUPPLIER_PAYMENT or SUPPLIER_REFUND", example = "RECEIPT")
        private String kind;
        @Schema(example = "RCPT/2026-27/0012")
        private String number;
        @Schema(example = "ISSUED")
        private String status;
        private LocalDate date;
        private BigDecimal amountInr;
        @Schema(description = "Balance still due, for an invoice or a supplier bill")
        private BigDecimal balanceInr;
        @Schema(example = "UPI")
        private String mode;
        @Schema(description = "Supplier name for supplier rows", example = "IndiGo")
        private String party;
        private boolean reversed;
        @Schema(description = "The invoice or bill this receipt, payment or credit note belongs to")
        private String parentId;
    }
}
