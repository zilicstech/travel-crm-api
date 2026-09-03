package com.voyra.crm.service;

import com.voyra.crm.entity.Lead;
import com.voyra.crm.enums.LeadStatus;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.repository.AgentRepository;
import com.voyra.crm.repository.LeadNoteRepository;
import com.voyra.crm.repository.LeadRepository;
import com.voyra.crm.repository.LeadMemberRepository;
import com.voyra.crm.repository.LeadProposalRepository;
import com.voyra.crm.repository.LeadServiceRepository;
import com.voyra.crm.repository.MemberRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/** The mock UI's original scoping bug, fixed: an agent may only ever touch their own leads. */
@ExtendWith(MockitoExtension.class)
class LeadScopingTest {

    @Mock
    private LeadRepository leadRepository;
    @Mock
    private LeadNoteRepository leadNoteRepository;
    @Mock
    private LeadProposalRepository leadProposalRepository;
    @Mock
    private LeadServiceRepository leadServiceRepository;
    @Mock
    private LeadMemberRepository leadMemberRepository;
    @Mock
    private MemberRepository memberRepository;
    @Mock
    private AgentRepository agentRepository;
    @Mock
    private ClientService clientService;
    @Mock
    private MemberService memberService;
    @Mock
    private LeadTimelineService leadTimelineService;
    @Mock
    private AuthorResolver authorResolver;
    @Mock
    private ServiceInstanceService serviceInstanceService;
    @Mock
    private LeadFollowUpService leadFollowUpService;
    @Mock
    private LeadVoucherService leadVoucherService;
    @Mock
    private InvoiceService invoiceService;

    @InjectMocks
    private LeadService leadService;

    private void authenticateAs(String userId, UserType role) {
        CustomUserPrincipal principal = new CustomUserPrincipal(userId, "user", role, "T1");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, java.util.List.of()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void agentCannotAccessALeadAssignedToSomeoneElse() {
        authenticateAs("A1", UserType.AGENT);
        Lead lead = Lead.builder().id("L1").assignedTo("A2").status(LeadStatus.NEW).build();
        when(leadRepository.findById("L1")).thenReturn(Optional.of(lead));

        assertThatThrownBy(() -> leadService.findAccessibleLead("L1"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void agentCanAccessTheirOwnLead() {
        authenticateAs("A1", UserType.AGENT);
        Lead lead = Lead.builder().id("L1").assignedTo("A1").status(LeadStatus.NEW).build();
        when(leadRepository.findById("L1")).thenReturn(Optional.of(lead));

        Lead result = leadService.findAccessibleLead("L1");

        assertThat(result.getId()).isEqualTo("L1");
    }

    @Test
    void ownerCanAccessAnyLeadInTheAgency() {
        authenticateAs("O1", UserType.AGENCY_OWNER);
        Lead lead = Lead.builder().id("L1").assignedTo("A2").status(LeadStatus.NEW).build();
        when(leadRepository.findById("L1")).thenReturn(Optional.of(lead));

        Lead result = leadService.findAccessibleLead("L1");

        assertThat(result.getId()).isEqualTo("L1");
    }
}
