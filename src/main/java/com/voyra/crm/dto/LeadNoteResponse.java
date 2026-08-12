package com.voyra.crm.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LeadNoteResponse {

    private String id;
    private String authorAgentId;
    private String authorName;
    private String text;
    private LocalDateTime createdDate;
}
