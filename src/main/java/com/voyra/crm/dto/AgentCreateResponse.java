package com.voyra.crm.dto;

import com.voyra.crm.enums.ServiceType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Result of adding a new Agent")
public class AgentCreateResponse {

    @Schema(description = "Generated Agent id", example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String id;

    @Schema(description = "Agent's display name", example = "Liam Smith")
    private String name;

    @Schema(description = "Agent's login email", example = "liam@globalexplorer.com")
    private String email;

    @Schema(description = "Agent's contact phone number", example = "+1 555 123 4567")
    private String phone;

    @Schema(description = "Which service types this agent may work on", example = "[\"FLIGHT\", \"HOTEL\"]")
    private List<ServiceType> manageableServices;
}
