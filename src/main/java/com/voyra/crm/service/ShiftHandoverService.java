package com.voyra.crm.service;

import com.voyra.crm.dto.ShiftHandoverCreateRequest;
import com.voyra.crm.dto.ShiftHandoverResponse;
import com.voyra.crm.entity.Agent;
import com.voyra.crm.entity.ShiftHandover;
import com.voyra.crm.repository.AgentRepository;
import com.voyra.crm.repository.ShiftHandoverRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import com.voyra.crm.security.SecurityContextUtil;
import com.voyra.crm.util.UniqueIdResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * A note per shift, not rostering/clock-in - see V17__shift_handover.sql. Any AGENT or the
 * AGENCY_OWNER may leave one; a null {@code toAgentId} addresses the whole team.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ShiftHandoverService {

    private final ShiftHandoverRepository shiftHandoverRepository;
    private final AgentRepository agentRepository;
    private final AuthorResolver authorResolver;

    @Transactional
    public ShiftHandoverResponse create(ShiftHandoverCreateRequest request) {
        AuthorResolver.AuthorInfo actor = authorResolver.resolveCurrentAuthor();

        String toAgentName = null;
        if (request.getToAgentId() != null && !request.getToAgentId().isBlank()) {
            Agent toAgent = agentRepository.findById(request.getToAgentId())
                    .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + request.getToAgentId()));
            toAgentName = toAgent.getName();
        }

        ShiftHandover handover = ShiftHandover.builder()
                .id(UniqueIdResolver.resolve(shiftHandoverRepository::existsById))
                .fromAgentId(actor.id())
                .fromAgentName(actor.name())
                .toAgentId(request.getToAgentId())
                .toAgentName(toAgentName)
                .summary(request.getSummary())
                .pinnedLeadIds(request.getPinnedLeadIds() != null ? request.getPinnedLeadIds() : List.of())
                .pinnedBookingIds(request.getPinnedBookingIds() != null ? request.getPinnedBookingIds() : List.of())
                .shiftEndedAt(request.getShiftEndedAt() != null ? request.getShiftEndedAt() : LocalDateTime.now())
                .createdAt(LocalDateTime.now())
                .build();
        shiftHandoverRepository.save(handover);
        log.info("Shift handover recorded: fromAgentId={}, toAgentId={}, id={}", actor.id(), request.getToAgentId(), handover.getId());
        return toResponse(handover);
    }

    /** Owner sees every handover; an agent sees ones addressed to them plus whole-team ones. */
    @Transactional(readOnly = true)
    public List<ShiftHandoverResponse> list() {
        CustomUserPrincipal principal = SecurityContextUtil.getCurrentUserOrThrow();
        List<ShiftHandover> rows = principal.isAgent()
                ? shiftHandoverRepository.findByToAgentIdOrToAgentIdIsNullOrderByShiftEndedAtDesc(principal.userId())
                : shiftHandoverRepository.findAllByOrderByShiftEndedAtDesc();
        return rows.stream().map(this::toResponse).toList();
    }

    @Transactional
    public ShiftHandoverResponse acknowledge(String id) {
        CustomUserPrincipal principal = SecurityContextUtil.getCurrentUserOrThrow();
        ShiftHandover handover = shiftHandoverRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Shift handover not found: " + id));

        if (principal.isAgent() && handover.getToAgentId() != null && !handover.getToAgentId().equals(principal.userId())) {
            throw new AccessDeniedException("This handover is not addressed to you");
        }

        if (handover.getAcknowledgedAt() == null) {
            AuthorResolver.AuthorInfo actor = authorResolver.resolveCurrentAuthor();
            handover.setAcknowledgedAt(LocalDateTime.now());
            handover.setAcknowledgedBy(actor.id());
            handover.setAcknowledgedByName(actor.name());
            shiftHandoverRepository.save(handover);
            log.info("Shift handover acknowledged: id={}, by={}", id, actor.id());
        }
        return toResponse(handover);
    }

    private ShiftHandoverResponse toResponse(ShiftHandover h) {
        return ShiftHandoverResponse.builder()
                .id(h.getId()).fromAgentId(h.getFromAgentId()).fromAgentName(h.getFromAgentName())
                .toAgentId(h.getToAgentId()).toAgentName(h.getToAgentName()).summary(h.getSummary())
                .pinnedLeadIds(h.getPinnedLeadIds()).pinnedBookingIds(h.getPinnedBookingIds())
                .shiftEndedAt(h.getShiftEndedAt()).acknowledgedAt(h.getAcknowledgedAt())
                .acknowledgedByName(h.getAcknowledgedByName()).createdAt(h.getCreatedAt())
                .build();
    }
}
