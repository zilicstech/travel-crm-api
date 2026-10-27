package com.voyra.crm.dto;

import com.voyra.crm.enums.KycDocumentStatus;
import com.voyra.crm.enums.KycDocumentType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@Schema(description = "A KYC document uploaded against an agency application")
public class KycDocumentResponse {

    @Schema(example = "0b6e4c1a-7f2d-4e8a-9c3b-5d1f2a6e8c4b")
    private String id;

    @Schema(example = "PAN")
    private KycDocumentType type;

    @Schema(example = "pan-card.pdf")
    private String fileName;

    @Schema(example = "248311")
    private long sizeBytes;

    @Schema(example = "PENDING")
    private KycDocumentStatus status;

    @Schema(description = "The platform desk's note on this document", example = "PAN card is unreadable")
    private String note;
}
