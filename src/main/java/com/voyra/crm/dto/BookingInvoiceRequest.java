package com.voyra.crm.dto;

import com.voyra.crm.enums.InvoiceServiceCategory;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;

@Data
@Schema(description = "Generate and issue the booking's tax invoice in one step. The body is built from the booking.")
public class BookingInvoiceRequest {

    @Schema(description = "Defaults from the booking type", example = "FLIGHT_DOMESTIC")
    private InvoiceServiceCategory serviceCategory;

    @Schema(example = "2026-10-31")
    private LocalDate dueDate;

    @Schema(example = "Thank you for booking with us")
    private String notes;
}
