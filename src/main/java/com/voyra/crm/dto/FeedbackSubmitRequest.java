package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "A customer's submitted rating/comment for a completed booking")
public class FeedbackSubmitRequest {

    @NotNull(message = "Rating is required")
    @Min(value = 1, message = "Rating must be between 1 and 5")
    @Max(value = 5, message = "Rating must be between 1 and 5")
    @Schema(example = "5")
    private Integer rating;

    @Size(max = 2000, message = "Comment must be 2000 characters or fewer")
    @Schema(example = "Wonderful trip, the itinerary was spot on!")
    private String comment;
}
