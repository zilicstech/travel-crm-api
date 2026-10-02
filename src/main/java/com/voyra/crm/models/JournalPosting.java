package com.voyra.crm.models;

import com.voyra.crm.enums.JournalPurpose;
import com.voyra.crm.enums.JournalSourceType;

import java.time.LocalDate;
import java.util.List;

/**
 * Input to {@link com.voyra.crm.service.JournalService#post} - the internal, programmatic path
 * every posting-rule-table event uses, distinct from {@code dto.JournalPostRequest}, which is
 * the validated shape the manual-journal HTTP endpoint accepts from a human. Never serialized.
 */
public record JournalPosting(
        LocalDate entryDate,
        JournalSourceType sourceType,
        String sourceId,
        JournalPurpose purpose,
        String narration,
        String bookingId,
        String branchId,
        List<JournalLinePosting> lines
) {
}
