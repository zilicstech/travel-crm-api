package com.voyra.crm.dto;

import com.voyra.crm.enums.FamilyRelation;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A family member linked to a Customer")
public class FamilyMemberResponse {

    @Schema(example = "F2H6J4")
    private String id;

    @Schema(example = "John Doe Jr.")
    private String name;

    @Schema(example = "CHILD")
    private FamilyRelation relation;

    @Schema(example = "2015-03-10")
    private LocalDate dob;

    private List<DocumentResponse> documents;
}
