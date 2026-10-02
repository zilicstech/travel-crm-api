package com.voyra.crm.dto;

import com.voyra.crm.enums.BankMatchField;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class BankMatchRuleRequest {

    @NotBlank(message = "name is required")
    @Schema(example = "Bank wire fee")
    private String name;

    @NotNull(message = "matchField is required")
    @Schema(example = "DESCRIPTION")
    private BankMatchField matchField;

    @NotBlank(message = "pattern is required")
    @Schema(example = "WIRE FEE")
    private String pattern;

    @NotBlank(message = "targetAccountCode is required")
    @Schema(example = "5620")
    private String targetAccountCode;

    @Schema(example = "true")
    private Boolean autoPost;

    @Schema(example = "100")
    private Integer priority;
}
