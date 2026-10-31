package com.voyra.crm.service;

import com.voyra.crm.dto.PublicProposalItemResponse;
import com.voyra.crm.dto.PublicProposalResponse;
import com.voyra.crm.entity.Lead;
import com.voyra.crm.entity.LeadProposal;
import com.voyra.crm.entity.LeadTimeline;
import com.voyra.crm.enums.LeadStatus;
import com.voyra.crm.enums.LeadTimelineEventType;
import com.voyra.crm.enums.ServiceStatus;
import com.voyra.crm.repository.LeadProposalRepository;
import com.voyra.crm.repository.LeadRepository;
import com.voyra.crm.repository.LeadServiceRepository;
import com.voyra.crm.repository.LeadTimelineRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PublicProposalTenantServiceCoverageTest {

    @Mock private LeadRepository leadRepository;
    @Mock private LeadProposalRepository leadProposalRepository;
    @Mock private LeadServiceRepository leadServiceRepository;
    @Mock private LeadTimelineService leadTimelineService;
    @Mock private LeadTimelineRepository leadTimelineRepository;
    @Mock private LeadService leadService;
    @Spy private BaseCurrency baseCurrency = new BaseCurrency(null);
    @InjectMocks private PublicProposalTenantService service;

    private static LeadProposal item(String id, String serviceId, String group, boolean selected, String price) {
        return LeadProposal.builder().id(id).leadId("L1").serviceId(serviceId).optionGroup(group).isSelected(selected)
                .description("Item " + id).sellingPrice(new BigDecimal(price)).build();
    }

    private static com.voyra.crm.entity.LeadService service(String id, ServiceStatus status) {
        return com.voyra.crm.entity.LeadService.builder().id(id).leadId("L1").label("Label " + id).status(status).build();
    }

    private void timeline(LeadTimelineEventType... newestFirst) {
        when(leadTimelineRepository.findByLeadIdOrderByCreatedAtDesc("L1")).thenReturn(java.util.Arrays.stream(newestFirst)
                .map(t -> LeadTimeline.builder().eventType(t).build()).toList());
    }

    @Test
    void anUnknownLeadHasNoProposal() {
        assertThat(service.fetchProposal("L9")).isEmpty();
        verifyNoInteractions(leadProposalRepository);
    }

    @Test
    void theProposalShowsLiveLinesAndTotalsTheChosenOnes() {
        Lead lead = Lead.builder().id("L1").clientName("Asha").destination("Goa").totalTravellers(3)
                .travelDateFrom(LocalDate.of(2026, 12, 1)).travelDateTo(LocalDate.of(2026, 12, 5)).proposalLocked(true).build();
        when(leadRepository.findById("L1")).thenReturn(Optional.of(lead));
        when(leadServiceRepository.findByLeadIdOrderBySortOrderAsc("L1"))
                .thenReturn(List.of(service("S1", ServiceStatus.IN_PROGRESS), service("S2", ServiceStatus.CANCELLED)));
        when(leadProposalRepository.findByLeadId("L1")).thenReturn(List.of(
                item("P1", null, null, false, "1000"),
                item("P2", "S1", "G1", true, "500"),
                item("P3", "S1", "G1", false, "900"),
                item("P4", "S2", null, false, "7000")));
        timeline(LeadTimelineEventType.NOTE_ADDED, LeadTimelineEventType.PROPOSAL_APPROVED, LeadTimelineEventType.PROPOSAL_ITEM_ADDED);

        PublicProposalResponse r = service.fetchProposal("L1").orElseThrow();

        assertThat(r.getItems()).extracting(PublicProposalItemResponse::getId).containsExactly("P1", "P2", "P3");
        assertThat(r.getItems().get(1).getServiceLabel()).isEqualTo("Label S1");
        assertThat(r.getItems().get(0).getServiceLabel()).isNull();
        assertThat(r.getGrandTotal()).isEqualByComparingTo("1500");
        assertThat(r.getGuestCount()).isEqualTo(3);
        assertThat(r.getCurrencyCode()).isEqualTo("INR");
        assertThat(r.getClientName()).isEqualTo("Asha");
        assertThat(r.getTravelDateTo()).isEqualTo(LocalDate.of(2026, 12, 5));
        assertThat(r.isLocked()).isTrue();
        assertThat(r.isApproved()).isTrue();
    }

    @Test
    void aChangeAfterTheApprovalReopensItAndAMissingHeadcountIsZero() {
        when(leadRepository.findById("L1")).thenReturn(Optional.of(Lead.builder().id("L1").totalTravellers(null).build()));
        timeline(LeadTimelineEventType.PROPOSAL_UNLOCKED, LeadTimelineEventType.PROPOSAL_APPROVED);

        PublicProposalResponse r = service.fetchProposal("L1").orElseThrow();

        assertThat(r.isApproved()).isFalse();
        assertThat(r.getGuestCount()).isZero();
        assertThat(r.getGrandTotal()).isEqualByComparingTo("0");
    }

    @Test
    void selectingOnAnUnknownLeadDoesNothing() {
        assertThat(service.selectOptions("L9", List.of("P1"))).isFalse();
        verifyNoInteractions(leadProposalRepository);
    }

    @Test
    void onlyOptionLinesOfThisLeadCanBeSelected() {
        when(leadRepository.findByIdForUpdate("L1")).thenReturn(Optional.of(Lead.builder().id("L1").build()));
        when(leadProposalRepository.findByLeadId("L1")).thenReturn(List.of(item("P1", null, null, false, "100")));

        assertThatThrownBy(() -> service.selectOptions("L1", List.of("OTHER")))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid selection: OTHER");
        assertThatThrownBy(() -> service.selectOptions("L1", List.of("P1")))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid selection: P1");
        verify(leadProposalRepository, never()).saveAll(any());
    }

    @Test
    void aPickChangesOnlyItsOwnBlockAndLocksTheProposal() {
        Lead lead = Lead.builder().id("L1").build();
        when(leadRepository.findByIdForUpdate("L1")).thenReturn(Optional.of(lead));
        LeadProposal addOn = item("P0", null, null, false, "50");
        LeadProposal a = item("A", null, "G1", true, "100");
        LeadProposal b = item("B", null, "G1", false, "200");
        LeadProposal other = item("C", null, "G2", false, "300");
        when(leadProposalRepository.findByLeadId("L1")).thenReturn(List.of(addOn, other, a, b));

        assertThat(service.selectOptions("L1", List.of("B"))).isTrue();

        assertThat(a.isSelected()).isFalse();
        assertThat(b.isSelected()).isTrue();
        assertThat(b.getSelectedBy()).isEqualTo("CUSTOMER");
        assertThat(b.getSelectedAt()).isNotNull();
        assertThat(a.getSelectedBy()).isNull();
        assertThat(other.isSelected()).isFalse();
        verify(leadProposalRepository).saveAll(List.of(a, b));
        assertThat(lead.isProposalLocked()).isTrue();
        verify(leadService).recomputeQuotedTotals(lead);
        verify(leadTimelineService).recordCustomerAction("L1", null, LeadTimelineEventType.PROPOSAL_OPTION_SELECTED,
                "Customer selected 1 option(s) on the shared proposal");
        verify(leadTimelineService).recordCustomerAction("L1", null, LeadTimelineEventType.PROPOSAL_LOCKED,
                "Proposal auto-locked after customer confirmed their choices");
    }

    @Test
    void approvingAnUnknownLeadDoesNothing() {
        assertThat(service.approve("L9")).isFalse();
        verifyNoInteractions(leadTimelineRepository);
    }

    @Test
    void approvalIgnoresCancelledBlocksAndKeepsALaterStatus() {
        Lead lead = Lead.builder().id("L1").build();
        lead.setStatus(LeadStatus.BOOKED);
        when(leadRepository.findByIdForUpdate("L1")).thenReturn(Optional.of(lead));
        timeline();
        when(leadServiceRepository.findByLeadIdOrderBySortOrderAsc("L1"))
                .thenReturn(List.of(service("S1", ServiceStatus.CONFIRMED), service("S2", ServiceStatus.CANCELLED)));
        when(leadProposalRepository.findByLeadId("L1")).thenReturn(List.of(
                item("P0", null, null, false, "10"),
                item("A", "S1", "G1", true, "100"),
                item("X", "S2", "G2", false, "200")));

        assertThat(service.approve("L1")).isTrue();

        assertThat(lead.getStatus()).isEqualTo(LeadStatus.BOOKED);
        assertThat(lead.isProposalLocked()).isTrue();
        verify(leadRepository).save(lead);
        verify(leadTimelineService).recordCustomerAction("L1", null, LeadTimelineEventType.PROPOSAL_APPROVED, "Customer approved the shared proposal");
    }
}
