package com.voyra.crm.service;

import com.voyra.crm.dto.LeadDetailResponse;
import com.voyra.crm.dto.LeadStatusUpdateRequest;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/** BRD rule: a lead marked Lost requires a mandatory reason; leaving Lost clears it. */
@ExtendWith(MockitoExtension.class)
class LeadBusinessRuleTest {

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
    @Mock
    private AuditService auditService;

    @InjectMocks
    private LeadService leadService;

    @BeforeEach
    void authenticateAsOwner() {
        CustomUserPrincipal principal = new CustomUserPrincipal("O1", "owner", UserType.AGENCY_OWNER, "T1");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, java.util.List.of()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void markingLostWithNullReasonThrows() {
        Lead lead = Lead.builder().id("L1").createdBy("A1").status(LeadStatus.NEGOTIATING).build();
        when(leadRepository.findById("L1")).thenReturn(Optional.of(lead));

        LeadStatusUpdateRequest request = new LeadStatusUpdateRequest();
        request.setStatus(LeadStatus.LOST);

        assertThatThrownBy(() -> leadService.updateStatus("L1", request))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void markingLostWithBlankReasonThrows() {
        Lead lead = Lead.builder().id("L1").createdBy("A1").status(LeadStatus.NEGOTIATING).build();
        when(leadRepository.findById("L1")).thenReturn(Optional.of(lead));

        LeadStatusUpdateRequest request = new LeadStatusUpdateRequest();
        request.setStatus(LeadStatus.LOST);
        request.setLostReason("   ");

        assertThatThrownBy(() -> leadService.updateStatus("L1", request))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void markingLostWithReasonSucceedsAndPersistsIt() {
        Lead lead = Lead.builder().id("L1").createdBy("A1").status(LeadStatus.NEGOTIATING).build();
        when(leadRepository.findById("L1")).thenReturn(Optional.of(lead));

        LeadStatusUpdateRequest request = new LeadStatusUpdateRequest();
        request.setStatus(LeadStatus.LOST);
        request.setLostReason("Budget too low");

        LeadDetailResponse response = leadService.updateStatus("L1", request);

        assertThat(response.getStatus()).isEqualTo(LeadStatus.LOST);
        assertThat(response.getLostReason()).isEqualTo("Budget too low");
    }

    @Test
    void movingAwayFromLostClearsTheReason() {
        Lead lead = Lead.builder().id("L1").createdBy("A1").status(LeadStatus.LOST)
                .lostReason("Budget too low").build();
        when(leadRepository.findById("L1")).thenReturn(Optional.of(lead));

        LeadStatusUpdateRequest request = new LeadStatusUpdateRequest();
        request.setStatus(LeadStatus.NEGOTIATING);

        LeadDetailResponse response = leadService.updateStatus("L1", request);

        assertThat(response.getStatus()).isEqualTo(LeadStatus.NEGOTIATING);
        assertThat(response.getLostReason()).isNull();
    }
}
