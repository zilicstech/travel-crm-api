package com.voyra.crm.service;

import com.voyra.crm.entity.Lead;
import com.voyra.crm.entity.LeadProposal;
import com.voyra.crm.entity.LeadTimeline;
import com.voyra.crm.enums.LeadStatus;
import com.voyra.crm.enums.LeadTimelineEventType;
import com.voyra.crm.repository.LeadProposalRepository;
import com.voyra.crm.repository.LeadRepository;
import com.voyra.crm.repository.LeadServiceRepository;
import com.voyra.crm.repository.LeadTimelineRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PublicProposalTenantServiceTest {

    @Mock private LeadRepository leadRepository;
    @Mock private LeadProposalRepository leadProposalRepository;
    @Mock private LeadServiceRepository leadServiceRepository;
    @Mock private LeadTimelineService leadTimelineService;
    @Mock private LeadTimelineRepository leadTimelineRepository;
    @InjectMocks private PublicProposalTenantService service;

    private Lead lead() {
        Lead lead = Lead.builder().id("L1").build();
        lead.setStatus(LeadStatus.PROPOSAL_SENT);
        return lead;
    }

    private LeadProposal option(String id, boolean selected) {
        return LeadProposal.builder().id(id).leadId("L1").optionGroup("G1")
                .sellingPrice(BigDecimal.TEN).isSelected(selected).build();
    }

    /** Approving with an option block undecided would hand the agency an incomplete trip. */
    @Test
    void approveIsRefusedWhileAnOptionBlockHasNoSavedChoice() {
        when(leadRepository.findById("L1")).thenReturn(Optional.of(lead()));
        when(leadTimelineRepository.findByLeadIdOrderByCreatedAtDesc("L1")).thenReturn(List.of());
        when(leadProposalRepository.findByLeadId("L1")).thenReturn(List.of(option("A", false), option("B", false)));

        assertThatThrownBy(() -> service.approve("L1")).isInstanceOf(IllegalStateException.class);
        verify(leadTimelineService, never()).recordCustomerAction(any(), any(), any(), any());
    }

    @Test
    void approveRecordsTheApprovalAndLocksTheChoices() {
        Lead lead = lead();
        when(leadRepository.findById("L1")).thenReturn(Optional.of(lead));
        when(leadTimelineRepository.findByLeadIdOrderByCreatedAtDesc("L1")).thenReturn(List.of());
        when(leadProposalRepository.findByLeadId("L1")).thenReturn(List.of(option("A", false), option("B", true)));

        assertThat(service.approve("L1")).isTrue();

        assertThat(lead.isProposalLocked()).isTrue();
        assertThat(lead.getStatus()).isEqualTo(LeadStatus.NEGOTIATING);
        verify(leadTimelineService).recordCustomerAction(eq("L1"), isNull(), eq(LeadTimelineEventType.PROPOSAL_APPROVED), any());
    }

    @Test
    void approvingTwiceIsANoOp() {
        when(leadRepository.findById("L1")).thenReturn(Optional.of(lead()));
        when(leadTimelineRepository.findByLeadIdOrderByCreatedAtDesc("L1")).thenReturn(List.of(
                LeadTimeline.builder().eventType(LeadTimelineEventType.PROPOSAL_APPROVED).build()));

        assertThat(service.approve("L1")).isTrue();
        verify(leadTimelineService, never()).recordCustomerAction(any(), any(), any(), any());
    }
}
