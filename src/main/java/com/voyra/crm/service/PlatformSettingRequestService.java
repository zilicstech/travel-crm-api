package com.voyra.crm.service;

import com.voyra.crm.dto.PlatformSettingRequestResponse;
import com.voyra.crm.entity.PlatformSettingRequest;
import com.voyra.crm.enums.PlatformAuditAction;
import com.voyra.crm.exception.NotFoundException;
import com.voyra.crm.repository.PlatformSettingRequestRepository;
import com.voyra.crm.security.SecurityContextUtil;
import com.voyra.crm.util.UniqueIdResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Setting changes that need a second platform admin (the slice 1 pricing second-approver rule): one admin requests, a
 * different admin, independent of the requester ({@link SecondApproverRule}), approves and the change applies at once;
 * either may withdraw an open request (and deactivating the requester withdraws it). Only
 * {@code tripjack_live_ready = true} goes this way - turning it off is a plain setting write.
 */
@Service
@RequiredArgsConstructor
public class PlatformSettingRequestService {

    private static final String ENTITY_TYPE = "PLATFORM_SETTING";
    private static final String PENDING = "PENDING";
    private static final String APPROVED = "APPROVED";
    private static final String WITHDRAWN = "WITHDRAWN";
    /** key=value pairs that may only be set by a second admin. */
    private static final Set<String> SECOND_APPROVER = Set.of(PlatformSettingService.TRIPJACK_LIVE_READY + "=true");

    private final PlatformSettingRequestRepository repository;
    private final PlatformSettingService settings;
    private final PlatformAuditService auditService;
    private final SecondApproverRule secondApproverRule;

    @Transactional
    public PlatformSettingRequestResponse request(String key, String value) {
        String v = value == null ? "" : value.trim();
        if (!SECOND_APPROVER.contains(key + "=" + v)) {
            throw new IllegalArgumentException("Only " + PlatformSettingService.TRIPJACK_LIVE_READY
                    + " = true needs a second platform admin; change other settings directly");
        }
        if (settings.tripjackLiveReady()) {
            throw new IllegalStateException(key + " is already on");
        }
        String waiting = "A change to " + key + " is already waiting for a second platform admin";
        if (repository.findFirstBySettingKeyAndDecidedAtIsNull(key).isPresent()) {
            throw new IllegalStateException(waiting);
        }
        PlatformSettingRequest saved;
        try {
            // flushed here so a concurrent request losing on the open-request index gets this 409, not a commit-time error
            saved = repository.saveAndFlush(PlatformSettingRequest.builder()
                    .id(UniqueIdResolver.resolve(repository::existsById))
                    .settingKey(key)
                    .value(v)
                    .requestedBy(SecurityContextUtil.getCurrentUserOrThrow().userId())
                    .requestedAt(Instant.now())
                    .build());
        } catch (DataIntegrityViolationException e) {
            throw new IllegalStateException(waiting);
        }
        audit(PlatformAuditAction.SETTING_CHANGE_REQUEST, saved);
        return toResponse(saved);
    }

    @Transactional
    public PlatformSettingRequestResponse approve(String id) {
        PlatformSettingRequest row = open(id);
        String actor = SecurityContextUtil.getCurrentUserOrThrow().userId();
        if (actor.equals(row.getRequestedBy())) {
            throw new IllegalStateException("A different platform admin must approve this change");
        }
        secondApproverRule.requireIndependent(actor, row.getRequestedBy(), row.getRequestedAt());
        decide(row, actor, APPROVED);
        settings.applyApproved(row.getSettingKey(), row.getValue());
        audit(PlatformAuditAction.SETTING_CHANGE_APPROVE, row);
        return toResponse(row);
    }

    @Transactional
    public PlatformSettingRequestResponse withdraw(String id) {
        PlatformSettingRequest row = open(id);
        decide(row, SecurityContextUtil.getCurrentUserOrThrow().userId(), WITHDRAWN);
        audit(PlatformAuditAction.SETTING_CHANGE_WITHDRAW, row);
        return toResponse(row);
    }

    @Transactional(readOnly = true)
    public List<PlatformSettingRequestResponse> pending() {
        return repository.findByDecidedAtIsNullOrderByRequestedAtAsc().stream().map(PlatformSettingRequestService::toResponse).toList();
    }

    /** The request under its row lock, still open: two admins deciding at once serialise and the second is refused. */
    private PlatformSettingRequest open(String id) {
        PlatformSettingRequest row = repository.findByIdForUpdate(id)
                .orElseThrow(() -> new NotFoundException("Setting change request not found: " + id));
        if (row.getDecidedAt() != null) {
            throw new IllegalStateException("This request was already " + row.getOutcome().toLowerCase(java.util.Locale.ROOT));
        }
        return row;
    }

    private void decide(PlatformSettingRequest row, String actor, String outcome) {
        row.setDecidedBy(actor);
        row.setDecidedAt(Instant.now());
        row.setOutcome(outcome);
        repository.save(row);
    }

    private void audit(PlatformAuditAction action, PlatformSettingRequest row) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("requestId", row.getId());
        detail.put("value", row.getValue());
        detail.put("requestedBy", row.getRequestedBy());
        if (row.getDecidedBy() != null) {
            detail.put("decidedBy", row.getDecidedBy());
        }
        auditService.record(action, ENTITY_TYPE, row.getSettingKey(), detail);
    }

    private static PlatformSettingRequestResponse toResponse(PlatformSettingRequest r) {
        return PlatformSettingRequestResponse.builder()
                .id(r.getId())
                .settingKey(r.getSettingKey())
                .value(r.getValue())
                .status(r.getOutcome() == null ? PENDING : r.getOutcome())
                .requestedBy(r.getRequestedBy())
                .requestedAt(r.getRequestedAt())
                .decidedBy(r.getDecidedBy())
                .decidedAt(r.getDecidedAt())
                .build();
    }
}
