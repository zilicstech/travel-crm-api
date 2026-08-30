package com.voyra.crm.service;

import com.voyra.crm.dto.LeadTimelineResponse;
import com.voyra.crm.entity.LeadTimeline;
import com.voyra.crm.enums.LeadStatus;
import com.voyra.crm.enums.LeadTimelineEventType;
import com.voyra.crm.repository.LeadTimelineRepository;
import com.voyra.crm.util.UniqueIdResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Writes and reads the lead activity stream.
 *
 * <p>Every method here is called from inside another service's transaction, so a timeline row
 * and the change it describes commit or roll back together. That is the whole point: an audit
 * trail that can disagree with the data it audits is worse than none.
 *
 * <p>There is deliberately no public create endpoint anywhere above this class. Timeline rows
 * are emitted by the server on state changes only; the moment a client can post arbitrary
 * entries the stream stops being evidence. Agent-authored prose belongs in lead notes instead.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LeadTimelineService {

    private final LeadTimelineRepository leadTimelineRepository;
    private final AuthorResolver authorResolver;

    /** Records an event with no status transition - member changes, notes, proposal edits. */
    @Transactional
    public void record(String leadId, LeadTimelineEventType eventType, String description) {
        recordTransition(leadId, eventType, null, null, description);
    }

    @Transactional
    public void recordTransition(String leadId, LeadTimelineEventType eventType,
                                 LeadStatus fromStatus, LeadStatus toStatus, String description) {
        AuthorResolver.AuthorInfo actor = authorResolver.resolveCurrentAuthor();
        LeadTimeline entry = LeadTimeline.builder()
                .id(UniqueIdResolver.resolve(leadTimelineRepository::existsById))
                .leadId(leadId)
                .eventType(eventType)
                .fromStatus(fromStatus)
                .toStatus(toStatus)
                .actorAgentId(actor.id())
                .actorName(actor.name())
                .description(truncate(description))
                .build();
        leadTimelineRepository.save(entry);
    }

    @Transactional(readOnly = true)
    public List<LeadTimelineResponse> listForLead(String leadId) {
        return leadTimelineRepository.findByLeadIdOrderByCreatedAtDesc(leadId).stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Descriptions are built from user-supplied text (a lost reason, a member name), so they
     * can exceed the column. Truncating beats letting a 500-character drop reason turn a
     * successful status change into a constraint violation.
     */
    private String truncate(String description) {
        return description.length() <= 500 ? description : description.substring(0, 497) + "...";
    }

    private LeadTimelineResponse toResponse(LeadTimeline entry) {
        return LeadTimelineResponse.builder()
                .id(entry.getId())
                .eventType(entry.getEventType())
                .fromStatus(entry.getFromStatus())
                .toStatus(entry.getToStatus())
                .actorAgentId(entry.getActorAgentId())
                .actorName(entry.getActorName())
                .description(entry.getDescription())
                .createdAt(entry.getCreatedAt())
                .build();
    }
}
