package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Retrieved login credentials - admin-only, every call is logged")
public class CredentialsResponse {

    @Schema(example = "CB9Y0N")
    private String id;

    @Schema(example = "liam@globalexplorer.com")
    private String email;

    @Schema(description = "Decrypted login password", example = "********")
    private String password;
}
