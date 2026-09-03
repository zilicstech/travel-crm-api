package com.voyra.crm.dto;

import com.voyra.crm.enums.ServiceType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

@Data
@Schema(description = "A promise to chase something on this lead. Omit serviceType for a "
        + "trip-level follow-up; set it to scope the promise to a service TYPE, not one instance "
        + "- \"chase the embassy Monday\" is Visa work whichever visa it is about.")
public class FollowUpCreateRequest {

    @NotNull(message = "Due date is required")
    @Schema(example = "2026-09-20")
    private LocalDate dueDate;

    @NotBlank(message = "Note is required")
    @Size(max = 500, message = "Note must be 500 characters or fewer")
    @Schema(example = "Chase the embassy for appointment confirmation")
    private String note;

    @NotBlank(message = "assignedAgentId is required")
    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String assignedAgentId;

    @Schema(description = "Null means trip-level", example = "VISA")
    private ServiceType serviceType;
}
