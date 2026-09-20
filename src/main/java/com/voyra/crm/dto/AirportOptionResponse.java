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
@Schema(description = "One row of the bundled airport/city reference list backing every location picker")
public class AirportOptionResponse {

    @Schema(example = "BLR")
    private String iata;

    @Schema(example = "Kempegowda International Airport")
    private String name;

    @Schema(example = "Bengaluru")
    private String city;

    @Schema(example = "IN")
    private String countryCode;

    @Schema(description = "Traffic proxy for result ranking: 0=large, 1=medium, 2=everything else", example = "0")
    private int sizeRank;
}
