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
@Schema(description = "A supplier that answered a search, with whether it takes bookings right now")
public class SearchSupplierResponse {

    @Schema(description = "Supplier id, as used in the suppliers filter")
    private String supplierId;
    @Schema(description = "Supplier name", example = "Tripjack")
    private String supplierName;
    @Schema(description = "False when the supplier is searchable but not taking bookings (Not taking bookings)")
    private boolean bookingEnabled;
}
