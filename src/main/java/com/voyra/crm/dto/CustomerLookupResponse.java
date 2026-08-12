package com.voyra.crm.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerLookupResponse {

    private boolean matched;
    private String customerId;
    private String name;
    private String email;
}
