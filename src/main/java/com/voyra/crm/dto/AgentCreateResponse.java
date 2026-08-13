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

    @Schema(description = "Generated Agent id", example = "CB9Y0N")
    private String id;

    @Schema(description = "Agent's display name", example = "Liam Smith")
    private String name;

    @Schema(description = "Agent's login email", example = "liam@globalexplorer.com")
    private String email;

    @Schema(description = "Agent's contact phone number", example = "+1 555 123 4567")
    private String phone;

    @Schema(description = "Agent's department", example = "SALES")
    private AgentDepartment department;

    @Schema(description = "Generated Agent login password. Also retrievable later via the credentials endpoint.")
    private String initialPassword;
}
