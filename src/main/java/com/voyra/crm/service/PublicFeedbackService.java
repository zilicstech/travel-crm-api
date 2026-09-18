package com.voyra.crm.service;

import com.voyra.crm.context.TenantContext;
import com.voyra.crm.dto.FeedbackSubmitRequest;
import com.voyra.crm.dto.PublicFeedbackResponse;
import com.voyra.crm.entity.FeedbackLink;
import com.voyra.crm.repository.FeedbackLinkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.function.Supplier;

/**
 * Unauthenticated entry point for the public feedback page. No JWT, no tenant claim - the
 * tenant is resolved purely from the opaque token via the public-schema feedback_link index,
 * then the actual read/write happens through {@link PublicFeedbackTenantService} in a fresh
 * transaction after the tenant context is set (blueprint §3.5's cross-tenant pattern).
 * Same "identical message for unknown/expired" reasoning as {@link PublicProposalService}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PublicFeedbackService {

    private static final String NOT_FOUND_MESSAGE = "This feedback link is invalid or has expired";

    private final FeedbackLinkRepository feedbackLinkRepository;
    private final PublicFeedbackTenantService tenantService;

    @Transactional(readOnly = true)
    public PublicFeedbackResponse getFeedbackPage(String token) {
        FeedbackLink link = resolveLink(token);
        return withTenant(link.getTenantId(), () -> tenantService.fetchFeedbackPage(link.getBookingId()))
                .orElseThrow(() -> new IllegalArgumentException(NOT_FOUND_MESSAGE));
    }

    /** Not readOnly: the tenant-scoped work this delegates to is a write. */
    @Transactional
    public void submit(String token, FeedbackSubmitRequest request) {
        FeedbackLink link = resolveLink(token);
        boolean submitted = withTenant(link.getTenantId(),
                () -> tenantService.submit(link.getBookingId(), link.getClientId(), request));
        if (!submitted) {
            throw new IllegalArgumentException(NOT_FOUND_MESSAGE);
        }
    }

    private FeedbackLink resolveLink(String token) {
        FeedbackLink link = feedbackLinkRepository.findById(token)
                .orElseThrow(() -> new IllegalArgumentException(NOT_FOUND_MESSAGE));
        if (link.getExpiresAt() != null && link.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new IllegalArgumentException(NOT_FOUND_MESSAGE);
        }
        return link;
    }

    /** try {set} finally {restore} - never leave a mutated ThreadLocal behind on a pooled request thread. */
    private <T> T withTenant(String tenantId, Supplier<T> work) {
        String previous = TenantContext.getTenantId();
        try {
            TenantContext.setTenantId(tenantId);
            return work.get();
        } finally {
            if (previous == null || previous.isBlank()) {
                TenantContext.clear();
            } else {
                TenantContext.setTenantId(previous);
            }
        }
    }
}
