package com.voyra.crm.dto;

import com.voyra.crm.enums.BookingDocumentType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "One voucher document attached to a booking")
public class BookingDocumentResponse {

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String id;

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String bookingId;

    @Schema(example = "E_TICKET")
    private BookingDocumentType docType;

    @Schema(example = "0987654321")
    private String referenceNumber;

    @Schema(example = "2026-09-20")
    private LocalDate issuedDate;

    @Schema(description = "Whether a file has been attached", example = "true")
    private boolean hasFile;

    @Schema(description = "Original filename of the attached file, if any", example = "emirates-eticket.pdf")
    private String fileName;

    @Schema(example = "Reissued after the airline's schedule change")
    private String notes;

    @Schema(example = "2026-08-13T09:15:22")
    private LocalDateTime createdAt;
}
