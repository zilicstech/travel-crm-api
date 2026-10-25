package com.voyra.crm.service;

import com.voyra.crm.dto.FeedbackSubmitRequest;
import com.voyra.crm.entity.FeedbackLink;
import com.voyra.crm.entity.ProposalLink;
import com.voyra.crm.exception.NotFoundException;
import com.voyra.crm.repository.FeedbackLinkRepository;
import com.voyra.crm.repository.ProposalLinkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
}
