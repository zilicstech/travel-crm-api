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
@Schema(description = "Phone-lookup result powering the Add Lead wizard's customer auto-fill")
public class CustomerLookupResponse {

    @Schema(description = "True if a customer with this phone number was found", example = "true")
    private boolean matched;

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String customerId;

    @Schema(example = "Jane Doe")
    private String name;

    @Schema(example = "jane.doe@example.com")
    private String email;
}
