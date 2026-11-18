package com.voyra.crm.dto;

import com.voyra.crm.enums.CancelReason;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

@Schema(description = "What Voyra cancelled at the supplier and the money to post; prefilled from the request, all editable")
public record CancelRequestCompleteRequest(
        @Schema(description = "The reason as Voyra records it; may differ from the agency's", example = "FLIGHT_CANCELLED_BY_AIRLINE")
        @NotNull(message = "Choose the final reason") CancelReason finalReason,
        @Schema(description = "Flight: passenger positions cancelled", example = "[0]") List<Integer> passengers,
        @Schema(description = "Flight: sector keys cancelled; empty means every sector", example = "[\"d0\"]") List<String> sectors,
        @Schema(description = "Refund the supplier gave Voyra", example = "4200.00")
        @NotNull(message = "Enter the supplier refund (0 or more)") @PositiveOrZero(message = "The supplier refund can't be negative")
        @Digits(integer = 12, fraction = 2, message = "At most two decimals") BigDecimal supplierRefund,
        @Schema(description = "The airline's or hotel's cancellation charges, shown to the agency", example = "800.00")
        @NotNull(message = "Enter the charges (0 or more)") @PositiveOrZero(message = "Charges can't be negative")
        @Digits(integer = 12, fraction = 2, message = "At most two decimals") BigDecimal charges,
        @Schema(description = "Voyra's own fee, taken off the refund; never shown to the agency", example = "100.00")
        @NotNull(message = "Enter Voyra's fee (0 or more)") @PositiveOrZero(message = "Voyra's fee can't be negative")
        @Digits(integer = 12, fraction = 2, message = "At most two decimals") BigDecimal voyraFee,
        @Schema(description = "Commission to take back from the agency", example = "45.00")
        @NotNull(message = "Enter the commission to take back (0 or more)") @PositiveOrZero(message = "The commission can't be negative")
        @Digits(integer = 12, fraction = 2, message = "At most two decimals") BigDecimal recall,
        @Schema(description = "The supplier's cancellation reference", example = "AMD-123456")
        @Size(max = 100, message = "The reference can be at most 100 characters") String supplierReference,
        @Schema(description = "Note shown to the agency", example = "Cancelled with the airline; refund as per fare rules")
        @Size(max = 1000, message = "The note can be at most 1000 characters") String note,
        @Schema(description = "Confirms a refund above the chosen passengers' share of the fare", example = "false") boolean exceedsShare) {
}
