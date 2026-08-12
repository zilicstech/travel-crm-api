package com.voyra.crm.dto;

import com.voyra.crm.enums.FamilyRelation;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

@Data
public class FamilyMemberCreateRequest {

    @NotBlank(message = "Name is required")
    private String name;

    @NotNull(message = "Relation is required")
    private FamilyRelation relation;

    private LocalDate dob;
}
