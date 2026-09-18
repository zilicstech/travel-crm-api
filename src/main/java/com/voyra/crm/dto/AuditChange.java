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
@Schema(description = "One field that changed. Values are stringified on write - a diff spans "
        + "BigDecimal, enum, LocalDate and String in one list and is only ever rendered as text.")
public class AuditChange {

    @Schema(description = "Java property name on the entity", example = "sellingPrice")
    private String field;

    @Schema(description = "Value before the change; null means the field was unset", example = "42000.00")
    private String oldValue;

    @Schema(description = "Value after the change; null means the field was cleared", example = "45500.00")
    private String newValue;
}
