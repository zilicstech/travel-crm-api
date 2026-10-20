package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "The bookings to bill on one new draft invoice")
public class InvoiceDraftsRequest {

    @NotEmpty(message = "Pick at least one booking")
    @Size(max = 100, message = "At most 100 bookings on one invoice")
    @Schema(description = "Booking ids - same client, supplier, agent and invoice type", example = "[\"8f2a1c3d-58cc-4372-a567-0e02b2c3d479\"]")
    private List<String> bookingIds;
}
