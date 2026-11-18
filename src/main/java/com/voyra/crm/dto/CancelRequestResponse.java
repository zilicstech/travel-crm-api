package com.voyra.crm.dto;

import com.voyra.crm.enums.CancelReason;
import com.voyra.crm.enums.CancelRequestStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** The agency's view of a cancellation request: never Voyra's fee, the supplier's refund or its reference. */
@Schema(description = "A cancellation request as the agency sees it")
public record CancelRequestResponse(
        @Schema(description = "Request id") String id,
        @Schema(description = "OPEN, IN_PROGRESS, COMPLETING, COMPLETED, REJECTED or WITHDRAWN", example = "OPEN") CancelRequestStatus status,
        @Schema(description = "The reason the agency chose", example = "MEDICALLY_UNFIT") CancelReason reason,
        @Schema(description = "The reason Voyra recorded on completion", example = "MEDICALLY_UNFIT") CancelReason finalReason,
        @Schema(description = "True when Voyra recorded a different reason than the agency chose") boolean reasonChanged,
        @Schema(description = "Passengers cancelled (or asked), by position, with names while they are on record") List<Passenger> passengers,
        @Schema(description = "Sectors cancelled (or asked); empty means every sector") List<CancelSectorResponse> sectors,
        @Schema(description = "The agency's remarks") String remarks,
        @Schema(description = "Proof files attached by the agency") List<FileRef> files,
        @Schema(description = "Refund credited to the wallet (completed only)", example = "3900.00") BigDecimal refund,
        @Schema(description = "Airline or hotel cancellation charges (completed only)", example = "800.00") BigDecimal charges,
        @Schema(description = "Commission taken back (completed only)", example = "45.00") BigDecimal recall,
        @Schema(description = "True when the wallet was closed and Voyra refunds by bank instead") boolean refundOffline,
        @Schema(description = "Voyra's note on completion or decline") String note,
        @Schema(description = "Who raised it", example = "Asha Rao") String requestedByName,
        @Schema(description = "When it was raised") Instant createdAt,
        @Schema(description = "When Voyra completed it") Instant completedAt) {

    @Schema(description = "A passenger of the request")
    public record Passenger(@Schema(description = "Position in the booking's passenger list", example = "0") int index,
                            @Schema(description = "Name as booked; null once passenger data is cleared", example = "Mr Rahul Sharma") String name,
                            @Schema(description = "ADULT, CHILD or INFANT", example = "ADULT") String type) {
    }

    @Schema(description = "A proof file")
    public record FileRef(@Schema(description = "File id") String id,
                          @Schema(description = "File name; null once the file was cleared", example = "certificate.pdf") String name) {
    }
}
