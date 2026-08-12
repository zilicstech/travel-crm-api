package com.voyra.crm.dto;

import com.voyra.crm.enums.FamilyRelation;
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
public class FamilyMemberResponse {

    private String id;
    private String name;
    private FamilyRelation relation;
    private LocalDate dob;
    private List<DocumentResponse> documents;
}
