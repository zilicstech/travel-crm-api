package com.voyra.crm.dto;

import com.voyra.crm.enums.LeadCategory;
import com.voyra.crm.enums.LeadPriority;
import com.voyra.crm.enums.LeadSource;
import com.voyra.crm.enums.LeadStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LeadResponse {

    private String id;
    private String customerId;
    private String name;
    private String email;
    private String countryCode;
    private String phone;
    private String destination;
    private LocalDate travelDateFrom;
    private LocalDate travelDateTo;
    private List<LeadCategory> categories;
    private String budget;
    private LeadStatus status;
    private LeadSource source;
    private LeadPriority priority;
    private String assignedTo;
    private String assignedAgentName;
    private LocalDate followUpDate;
    private LocalDateTime createdDate;

    @Schema(description = "followUpDate is in the past AND status is not BOOKED/LOST")
    private boolean overdue;
}
