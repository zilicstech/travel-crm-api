package com.voyra.crm.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class InteractionCreateRequest {

    @NotBlank(message = "Note text is required")
    private String note;
}
