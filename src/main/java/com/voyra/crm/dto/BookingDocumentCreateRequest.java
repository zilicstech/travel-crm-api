package com.voyra.crm.dto;

import com.voyra.crm.enums.BookingDocumentType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

@Data
@Schema(description = "A voucher document to attach to a booking - an e-ticket, a hotel voucher, "
        + "an insurance certificate. The file itself is uploaded separately via the /file endpoint.")
public class BookingDocumentCreateRequest {

    @NotNull(message = "Document type is required")
    @Schema(example = "E_TICKET")
    private BookingDocumentType docType;

    @Schema(example = "0987654321")
    private String referenceNumber;

    @Schema(example = "2026-09-20")
    private LocalDate issuedDate;

    @Schema(example = "Reissued after the airline's schedule change")
    private String notes;
}
