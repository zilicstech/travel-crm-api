package com.voyra.crm.dto;

import com.voyra.crm.enums.VisaFieldScope;
import com.voyra.crm.enums.VisaFieldType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Adds or edits one visa field. On edit the scope, country and type must stay as they are.")
public class VisaFieldRequest {

    @NotNull(message = "Pick common or country")
    @Schema(description = "COMMON (every country) or COUNTRY (one country)", example = "COUNTRY")
    private VisaFieldScope scope;

    @Size(max = 100, message = "Country must be 100 characters or fewer")
    @Schema(description = "Required for a COUNTRY field, as the country picker spells it; ignored for COMMON", example = "United Arab Emirates")
    private String country;

    @NotBlank(message = "Label is required")
    @Size(max = 60, message = "Label must be 60 characters or fewer")
    @Schema(example = "MEDICAL")
    private String label;

    @NotNull(message = "Pick a type")
    @Schema(example = "DROPDOWN")
    private VisaFieldType type;

    @Schema(description = "Dropdown only: 1-30 options, each 1-40 characters, unique ignoring case. On edit, an option at the same position with new text is a rename (old values are rewritten).",
            example = "[\"RECVD\", \"PENDING\"]")
    private List<String> options;

    @Schema(description = "Must be filled when an application is opened", example = "true")
    private boolean mandatory;
}
