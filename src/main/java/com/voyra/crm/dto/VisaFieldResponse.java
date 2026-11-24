package com.voyra.crm.dto;

import com.voyra.crm.enums.VisaFieldScope;
import com.voyra.crm.enums.VisaFieldType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "One configured visa field")
public class VisaFieldResponse {

    @Schema(example = "f1a2b3")
    private String id;

    @Schema(example = "COUNTRY")
    private VisaFieldScope scope;

    @Schema(description = "Null for a COMMON field", example = "United Arab Emirates")
    private String country;

    @Schema(example = "MEDICAL")
    private String label;

    @Schema(example = "DROPDOWN")
    private VisaFieldType type;

    @Schema(description = "Dropdown options, empty for other types", example = "[\"RECVD\", \"PENDING\"]")
    private List<String> options;

    @Schema(example = "true")
    private boolean mandatory;

    @Schema(example = "0")
    private int sortOrder;

    @Schema(description = "False once removed; a removed field's values still show on old applications", example = "true")
    private boolean active;
}
