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
@Schema(description = "One row of the bundled country reference list backing the visa/country picker")
public class CountryOptionResponse {

    @Schema(example = "IN")
    private String code;

    @Schema(example = "India")
    private String name;
}
