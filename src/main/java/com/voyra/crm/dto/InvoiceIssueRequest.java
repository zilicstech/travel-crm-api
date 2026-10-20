package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import lombok.Data;

import java.util.List;

/** Generating a draft: optionally with the supplier bills of the vouchers it bills ("Purchase ?" in ePrompt). */
@Data
@Schema(description = "Generate the invoice; each purchase entry books that voucher's supplier bill in the same step")
public class InvoiceIssueRequest {

    @Valid
    @Schema(description = "The vouchers whose supplier bills are booked with this invoice")
    private List<PurchaseEntryRequest> purchases;
}
