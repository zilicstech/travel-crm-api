package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "One booking an invoice bills")
public class InvoiceBookingRefResponse {

    @Schema(example = "8f2a1c3d-58cc-4372-a567-0e02b2c3d479")
    private String id;

    @Schema(example = "BK-00042")
    private String bookingRef;

    @Schema(description = "The booking's supplier", example = "MMT")
    private String supplier;
}
