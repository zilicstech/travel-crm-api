package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "Hotels of a search result whose photo, stars and address are wanted - asked after the list is shown")
public class HotelThumbnailsRequest {

    @NotEmpty(message = "At least one hotel id is required")
    @Size(max = 100, message = "At most 100 hotels per request")
    @Schema(description = "Supplier hotel ids from the search", example = "[\"10000000012345\"]")
    private List<String> hotelIds;
}
