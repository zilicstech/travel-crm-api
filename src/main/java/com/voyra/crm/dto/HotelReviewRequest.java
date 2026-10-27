package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
@Schema(description = "Re-price a hotel room option with the supplier before booking it")
public class HotelReviewRequest {

    @NotBlank
    @Size(max = 120)
    @Schema(description = "Hotel id from the hotel search", example = "mock-hotel-goa-1")
    private String hotelId;

    @NotBlank
    @Size(max = 200)
    @Schema(description = "Room option id from the hotel detail", example = "mock-opt-1")
    private String optionId;

    @NotNull
    @Schema(description = "Check-in date", example = "2026-12-01")
    private LocalDate checkIn;

    @NotNull
    @Schema(description = "Check-out date, after check-in", example = "2026-12-03")
    private LocalDate checkOut;

    @NotEmpty
    @Size(max = 9)
    @Valid
    @Schema(description = "One entry per room, as searched")
    private List<HotelRoomRequest> rooms;

    @Schema(description = "Optional CRM client the booking is for", example = "c1d2e3f4-0000-0000-0000-000000000001")
    private String clientId;

    @Schema(description = "Optional CRM lead the booking belongs to", example = "l1d2e3f4-0000-0000-0000-000000000001")
    private String leadId;

    @Schema(description = "Optional lead service tab the booking was launched from", example = "s1d2e3f4-0000-0000-0000-000000000001")
    private String serviceId;

    @Schema(description = "Optional displayed price the agency saw on the hotel detail; when the review differs, priceChanged is true", example = "12500.00")
    private BigDecimal searchDisplayedFare;

    @Schema(description = "Owner booking for a CRM client: the agent the booking is issued by (owns the CRM copy)", example = "a1b2c3")
    private String issuedByAgentId;

    @Size(max = 200)
    @Schema(description = "Display hint: hotel name, shown on the booking", example = "Sea Breeze Resort")
    private String hotelName;

    @Size(max = 100)
    @Schema(description = "Display hint: city, shown on the booking", example = "Goa")
    private String city;

    @Size(max = 300)
    @Schema(description = "Display hint: hotel address, shown on the booking", example = "Calangute Beach Road, Goa")
    private String address;

    @Size(max = 200)
    @Schema(description = "Display hint: room name, shown on the booking", example = "Deluxe Sea View")
    private String roomName;

    @Size(max = 100)
    @Schema(description = "Display hint: meal basis, shown on the booking", example = "Breakfast included")
    private String mealBasis;

}
