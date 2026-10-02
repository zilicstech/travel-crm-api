package com.voyra.crm.dto;

import com.voyra.crm.enums.BankMatchField;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class BankMatchRuleResponse {

    @Schema(example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String id;

    @Schema(example = "Bank wire fee")
    private String name;

    @Schema(example = "DESCRIPTION")
    private BankMatchField matchField;

    @Schema(example = "WIRE FEE")
    private String pattern;

    @Schema(example = "5620")
    private String targetAccountCode;

    @Schema(example = "true")
    private Boolean autoPost;

    @Schema(example = "100")
    private Integer priority;

    @Schema(example = "true")
    private Boolean isActive;
}
