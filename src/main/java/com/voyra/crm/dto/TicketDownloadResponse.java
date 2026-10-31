package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

/** One recorded e-ticket download. Hand-built: never carries the airline base fare, the agency's net, commission or Voyra figures. */
@Data
@Builder
@Schema(description = "A customer e-ticket download")
public class TicketDownloadResponse {

    @Schema(description = "Download id", example = "t1d2e3f4-0000-0000-0000-000000000001")
    private String id;

    @Schema(description = "1, 2, 3... per booking", example = "1")
    private int seq;

    @Schema(description = "When it was downloaded", example = "2026-10-31T05:00:00Z")
    private Instant createdAt;

    @Schema(description = "Who downloaded it", example = "Olga Owner")
    private String createdByName;

    @Schema(description = "Markup added for the client", example = "500.00")
    private BigDecimal markup;

    @Schema(description = "Price printed on the ticket: published fare plus markup", example = "5500.00")
    private BigDecimal ticketPrice;
}
