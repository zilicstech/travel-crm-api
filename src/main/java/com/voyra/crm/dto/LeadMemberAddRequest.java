package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "Adds travellers to a lead. Supply memberIds to pick people already on "
        + "the client's roster, and newMembers to create and attach ad-hoc travellers in the "
        + "same call. Either may be used alone or both together, but at least one must be "
        + "non-empty. Ad-hoc travellers join the lead's client roster so they are reusable on "
        + "the client's next enquiry.")
public class LeadMemberAddRequest {

    @Schema(description = "Existing members of this lead's client to attach as travellers")
    private List<String> memberIds;

    @Valid
    @Schema(description = "Ad-hoc travellers to create on the client and attach in one step")
    private List<MemberCreateRequest> newMembers;
}
