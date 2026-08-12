package com.voyra.crm.dto;

import com.voyra.crm.enums.AgentDepartment;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Result of adding a new Agent - carries the generated password once, on creation only")
public class AgentCreateResponse {

    private String id;
    private String name;
    private String email;
    private String phone;
    private AgentDepartment department;

    @Schema(description = "Generated Agent login password. Also retrievable later via the credentials endpoint.")
    private String initialPassword;
}
