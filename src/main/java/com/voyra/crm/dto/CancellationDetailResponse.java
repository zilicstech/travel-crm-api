package com.voyra.crm.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A booking's cancellation: its row, follow-ups, Voyra requests and activity")
public class CancellationDetailResponse {

    @Schema(description = "The same row the list shows")
    private CancellationRowResponse row;

    @Schema(description = "Every follow-up, earliest due first")
    private List<CancellationFollowUpResponse> followUps;

    @Schema(description = "The booking's Voyra cancel requests, newest last (agency view)")
    private List<CancelRequestResponse> voyraRequests;

    @Schema(description = "What people did on the booking, newest first")
    private List<BookingActivityResponse> activity;
}
