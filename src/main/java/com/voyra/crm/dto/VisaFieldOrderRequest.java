package com.voyra.crm.dto;

import com.voyra.crm.enums.VisaFieldScope;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "The new order of the active fields of one scope and country")
public class VisaFieldOrderRequest {

    @NotNull(message = "Pick common or country")
    @Schema(example = "COMMON")
    private VisaFieldScope scope;

    @Schema(description = "Required for COUNTRY", example = "Kenya")
    private String country;

    @NotEmpty(message = "Give the fields in their new order")
    @Schema(description = "Every active field id of that scope and country, in the new order", example = "[\"f2\", \"f1\"]")
    private List<String> ids;
}
