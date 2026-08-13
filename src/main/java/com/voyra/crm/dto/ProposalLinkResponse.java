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
@Schema(description = "The generated public share link for a Lead's proposal")
public class ProposalLinkResponse {

    @Schema(description = "High-entropy token, unguessable - not the lead's own id")
    private String token;

    @Schema(description = "Full shareable URL for the customer-facing proposal page", example = "http://localhost:3000/proposal/AB12CD34EF56GH78IJ90KL12MN34OP56")
    private String url;
}
