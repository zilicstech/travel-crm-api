package com.voyra.crm.dto;

import com.voyra.crm.enums.FamilyRelation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

@Data
@Schema(description = "Request body for adding a family member to a Customer")
public class FamilyMemberCreateRequest {

    @NotBlank(message = "Name is required")
    @Schema(example = "John Doe Jr.")
    private String name;

    @NotNull(message = "Relation is required")
    @Schema(example = "CHILD")
    private FamilyRelation relation;

    @Schema(example = "2015-03-10")
    private LocalDate dob;
}
