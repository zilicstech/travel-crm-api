package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Occupancy of one hotel room")
public class HotelRoomRequest {

    @Min(1)
    @Max(8)
    @Schema(description = "Adults in the room", example = "2")
    private int adults;

    @Size(max = 4)
    @Schema(description = "Age of each child in the room (0-17)", example = "[6]")
    private List<@NotNull @Min(0) @Max(17) Integer> childAges;
}
