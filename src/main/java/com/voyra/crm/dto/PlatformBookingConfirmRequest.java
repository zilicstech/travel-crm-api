package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
@Schema(description = "Confirm a reviewed booking: hold the wallet and book with the supplier")
public class PlatformBookingConfirmRequest {

    @NotBlank
    @Size(min = 8, max = 80)
    @Schema(description = "Client-generated id; a double click with the same id returns the same booking", example = "5b1f0c1e-9a3d-4c55-8d0e-2f6c1a7b9e11")
    private String idempotencyKey;

    @NotNull
    @Schema(description = "The displayed fare the agency accepted; must equal the reviewed fare", example = "4500.00")
    private BigDecimal acceptedDisplayedFare;

    @NotEmpty
    @Valid
    @Schema(description = "Flights: one entry per traveller, matching the reviewed party size. Hotels: every reviewed guest, room by room (roomIndex), the lead first")
    private List<PaxInput> passengers;

    @NotBlank
    @Email
    @Schema(description = "Contact email sent to the airline", example = "bookings@agency.test")
    private String contactEmail;

    @NotBlank
    @Size(max = 20)
    @Schema(description = "Contact phone sent to the airline", example = "9876543210")
    private String contactPhone;

    @jakarta.validation.constraints.Pattern(regexp = "\\d{1,4}", message = "Country code must be 1 to 4 digits")
    @Schema(description = "The contact phone's country dialling code, digits only (hotels send it to the supplier on its own); "
            + "absent means India (91)", example = "91")
    private String contactDialCode;
}
