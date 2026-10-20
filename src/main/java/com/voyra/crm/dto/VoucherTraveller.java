package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A traveller on an air ticket voucher: one of the client's members, as the ticket names them. The
 * name and pax type are a snapshot taken when the voucher is saved - the name on a ticket doesn't
 * follow a later rename of the member.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A traveller on the voucher - send memberId; the server fills name and paxType")
public class VoucherTraveller {

    @Schema(description = "The client's member travelling on this ticket", example = "3f2c9a1e-...")
    private String memberId;

    @Schema(description = "Set by the server from the member", example = "ABHISHEK KUMAR SINGH")
    private String name;

    @Schema(description = "Set by the server from the member's date of birth on the first leg's date", example = "ADULT")
    private com.voyra.crm.enums.PaxType paxType;
}
