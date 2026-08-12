package com.voyra.crm.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Generic API error/failure response")
public class ApiErrorResponse {

    @Schema(description = "Always false for error responses")
    private boolean success = false;

    @Schema(description = "Human-readable error message")
    private String message;

    @Schema(description = "Short reason/category for the failure")
    private String reason;

    @Schema(description = "Root cause (debugging aid)")
    private String cause;

    @Schema(description = "HTTP status code")
    private int status;

    @Schema(description = "Request path that caused the error")
    private String path;

    @Schema(description = "ISO-8601 timestamp")
    private String timestamp;

    @Schema(description = "Additional details, e.g. validation field errors")
    private Map<String, Object> details;

    public static ApiErrorResponse of(String message, String reason, String cause,
                                       int status, String path) {
        return ApiErrorResponse.builder()
                .success(false)
                .message(message != null ? message : "An error occurred")
                .reason(reason)
                .cause(cause)
                .status(status)
                .path(path)
                .timestamp(Instant.now().toString())
                .build();
    }
}
