package com.voyra.crm.dto;

import com.voyra.crm.enums.BoardingPassStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A flight booking travelling on one date, and what was done about its boarding pass")
public class BoardingPassRowResponse {

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String bookingId;

    @Schema(example = "BK00000042")
    private String bookingRef;

    @Schema(example = "2026-11-23")
    private LocalDate travelDate;

    @Schema(description = "ONWARD, RETURN or FLIGHT (a date read from a ticket voucher)", example = "ONWARD")
    private String leg;

    @Schema(example = "Delhi to Mumbai")
    private String destination;

    @Schema(example = "ABC123")
    private String pnr;

    @Schema(example = "Ajay Sharma")
    private String clientName;

    @Schema(example = "Vikram Rao")
    private String agentName;

    @Schema(description = "Who travels that day (passengers Voyra cancelled on that journey are left out)")
    private List<String> passengers;

    @Schema(description = "Null while still to do", example = "GENERATED")
    private BoardingPassStatus status;

    @Schema(description = "Why it is not needed, when given", example = "Client checks in himself")
    private String reason;

    @Schema(description = "The boarding pass row, when one exists", example = "d1e2")
    private String boardingPassId;

    @Schema(description = "The proof file's name, when one was added", example = "boarding-pass.pdf")
    private String fileName;

    @Schema(example = "Vikram Rao")
    private String updatedByName;

    @Schema(example = "2026-11-22T18:30:00")
    private LocalDateTime updatedAt;
}
