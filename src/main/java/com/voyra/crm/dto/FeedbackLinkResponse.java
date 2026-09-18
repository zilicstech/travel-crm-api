package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "The generated public share link for requesting feedback on a booking")
public class FeedbackLinkResponse {

    @Schema(description = "High-entropy token, unguessable - not the booking's own id")
    private String token;

    @Schema(description = "Full shareable URL for the customer-facing feedback page", example = "http://localhost:3000/feedback/AB12CD34EF56GH78IJ90KL12MN34OP56")
    private String url;
}
