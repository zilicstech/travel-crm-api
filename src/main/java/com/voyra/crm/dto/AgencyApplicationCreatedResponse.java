package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@Schema(description = "A new draft application and its one-time upload token")
public class AgencyApplicationCreatedResponse {

    @Schema(example = "6f1c2a4e-3b8d-4c1a-9e2f-7d5b0a1c3e9f")
    private String id;

    @Schema(description = "Send as the X-Upload-Token header to upload documents and submit; valid 24 hours, shown only once",
            example = "q3Jx0bG9vZ1N0cm9uZ1Rva2VuX2V4YW1wbGVfMzJieXRlcw")
    private String uploadToken;
}
