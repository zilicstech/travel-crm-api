package com.voyra.crm.service;

import com.voyra.crm.cache.TenantCache;
import com.voyra.crm.dto.FeedbackSubmitRequest;
import com.voyra.crm.enums.Sku;
import com.voyra.crm.enums.SubscriptionPlan;
import com.voyra.crm.enums.SubscriptionStatus;
import com.voyra.crm.enums.TenantStatus;
import com.voyra.crm.models.Entitlements;
import com.voyra.crm.repository.SubscriptionRepository;
import org.springframework.transaction.PlatformTransactionManager;
import com.voyra.crm.entity.FeedbackLink;
import com.voyra.crm.entity.ProposalLink;
import com.voyra.crm.exception.NotFoundException;
import com.voyra.crm.repository.FeedbackLinkRepository;
import com.voyra.crm.repository.ProposalLinkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** An agency without CRM Core, or READ_ONLY, no longer serves its public proposal and feedback links (spec §8). */
@ExtendWith(MockitoExtension.class)
class PublicLinkAvailabilityTest {

    private static final String GONE = "This link is no longer available";

    @Mock private ProposalLinkRepository proposalLinkRepository;
    @Mock private PublicProposalTenantService proposalTenantService;
    @Mock private FeedbackLinkRepository feedbackLinkRepository;
    @Mock private PublicFeedbackTenantService feedbackTenantService;
    @Mock private EntitlementService entitlementService;
    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private PlatformTransactionManager transactionManager;

    private PublicProposalService proposals;
    private PublicFeedbackService feedback;

    @BeforeEach
    void setUp() {
        proposals = new PublicProposalService(proposalLinkRepository, proposalTenantService, entitlementService);
        feedback = new PublicFeedbackService(feedbackLinkRepository, feedbackTenantService, entitlementService);
        when(proposalLinkRepository.findById("P")).thenReturn(Optional.of(ProposalLink.builder().token("P").tenantId("T1").leadId("L1").build()));
        when(feedbackLinkRepository.findById("F")).thenReturn(Optional.of(FeedbackLink.builder().token("F").tenantId("T1").bookingId("B1").build()));
    }

    @Test
    void unavailableAgencyRefusesEveryProposalAndFeedbackAction() {
        when(entitlementService.publicLinksAvailable("T1")).thenReturn(false);

        assertThatThrownBy(() -> proposals.getProposal("P")).isInstanceOf(NotFoundException.class).hasMessage(GONE);
        assertThatThrownBy(() -> proposals.approveProposal("P")).isInstanceOf(NotFoundException.class).hasMessage(GONE);
        assertThatThrownBy(() -> proposals.selectOptions("P", List.of("I1"))).isInstanceOf(NotFoundException.class).hasMessage(GONE);
        assertThatThrownBy(() -> feedback.getFeedbackPage("F")).hasMessage(GONE);
        assertThatThrownBy(() -> feedback.submit("F", new FeedbackSubmitRequest())).hasMessage(GONE);
        verifyNoInteractions(proposalTenantService, feedbackTenantService);
    }

    @Test
    void availableAgencyServesTheLink() {
        when(entitlementService.publicLinksAvailable("T1")).thenReturn(true);
        when(proposalTenantService.approve("L1")).thenReturn(true);
        when(feedbackTenantService.submit(anyString(), any(), any())).thenReturn(true);

        proposals.approveProposal("P");
        feedback.submit("F", new FeedbackSubmitRequest());

        verify(proposalTenantService).approve("L1");
        verify(feedbackTenantService).submit(anyString(), any(), any());
    }

    /** Spec §8 via the real EntitlementService: a Suite agency that is SUSPENDED or DEACTIVATED serves no link. */
    @ParameterizedTest
    @EnumSource(value = TenantStatus.class, names = {"SUSPENDED", "DEACTIVATED"})
    void suspendedOrDeactivatedAgencyRefusesEveryAction(TenantStatus status) {
        EntitlementService real = new EntitlementService(subscriptionRepository, transactionManager);
        proposals = new PublicProposalService(proposalLinkRepository, proposalTenantService, real);
        feedback = new PublicFeedbackService(feedbackLinkRepository, feedbackTenantService, real);
        TenantCache.put("T1", "Sky", status);
        TenantCache.putEntitlements("T1", new Entitlements(SubscriptionPlan.SUITE, SubscriptionStatus.ACTIVE,
                EnumSet.allOf(Sku.class), 5, false, null));
        try {
            assertThatThrownBy(() -> proposals.getProposal("P")).hasMessage(GONE);
            assertThatThrownBy(() -> proposals.approveProposal("P")).hasMessage(GONE);
            assertThatThrownBy(() -> proposals.selectOptions("P", List.of("I1"))).hasMessage(GONE);
            assertThatThrownBy(() -> feedback.getFeedbackPage("F")).hasMessage(GONE);
            assertThatThrownBy(() -> feedback.submit("F", new FeedbackSubmitRequest())).hasMessage(GONE);
            verifyNoInteractions(proposalTenantService, feedbackTenantService);
        } finally {
            TenantCache.remove("T1");
        }
    }
}
