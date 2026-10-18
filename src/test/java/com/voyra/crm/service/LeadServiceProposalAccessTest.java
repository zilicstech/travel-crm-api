package com.voyra.crm.service;

import com.voyra.crm.dto.ProposalItemCreateRequest;
import com.voyra.crm.entity.Lead;
import com.voyra.crm.entity.LeadProposal;
import com.voyra.crm.enums.LeadStatus;
import com.voyra.crm.enums.MarkupMode;
import com.voyra.crm.enums.ProposalItemType;
import com.voyra.crm.enums.ServiceType;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.repository.AgentRepository;
import com.voyra.crm.repository.LeadMemberRepository;
import com.voyra.crm.repository.LeadNoteRepository;
import com.voyra.crm.repository.LeadProposalRepository;
import com.voyra.crm.repository.LeadRepository;
import com.voyra.crm.repository.LeadServiceRepository;
import com.voyra.crm.repository.MemberRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Proposal lines on a service are that service's work: only someone who can edit the service may change them. */
@ExtendWith(MockitoExtension.class)
class LeadServiceProposalAccessTest {

    @Mock private LeadRepository leadRepository;
    @Mock private LeadNoteRepository leadNoteRepository;
    @Mock private LeadProposalRepository leadProposalRepository;
    @Mock private LeadServiceRepository leadServiceRepository;
    @Mock private LeadMemberRepository leadMemberRepository;
    @Mock private MemberRepository memberRepository;
    @Mock private AgentRepository agentRepository;
    @Mock private ClientService clientService;
    @Mock private MemberService memberService;
    @Mock private LeadTimelineService leadTimelineService;
    @Mock private AuthorResolver authorResolver;
    @Mock private ServiceInstanceService serviceInstanceService;
    @Mock private LeadFollowUpService leadFollowUpService;
    @Mock private BookingService bookingService;
    @Mock private InvoiceService invoiceService;
    @Mock private AuditService auditService;
    @InjectMocks private LeadService leadService;

    private final com.voyra.crm.entity.LeadService flight = com.voyra.crm.entity.LeadService.builder()
            .id("S1").leadId("L1").type(ServiceType.FLIGHT).label("Flight").build();

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal("O1", "owner", UserType.AGENCY_OWNER, "T1"), null, List.of()));
        when(leadRepository.findById("L1")).thenReturn(Optional.of(Lead.builder().id("L1").createdBy("A1").status(LeadStatus.NEW).build()));
        lenient().when(leadServiceRepository.findById("S1")).thenReturn(Optional.of(flight));
        lenient().when(leadProposalRepository.existsById(any())).thenReturn(false);
        lenient().when(leadProposalRepository.findByIdAndLeadId(any(), any())).thenReturn(Optional.empty());
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private ProposalItemCreateRequest line(String serviceId) {
        ProposalItemCreateRequest r = new ProposalItemCreateRequest();
        r.setType(ProposalItemType.FLIGHT);
        r.setServiceId(serviceId);
        r.setDescription("DEL-BOM");
        r.setNetCost(new BigDecimal("1000"));
        r.setSellingPrice(new BigDecimal("5"));
        r.setMarkupMode(MarkupMode.PERCENT);
        r.setMarkupValue(new BigDecimal("10"));
        return r;
    }

    @Test
    void someoneWhoCannotEditTheServiceCannotAddItsLines() {
        doThrow(new AccessDeniedException("Only agents who handle FLIGHT can update this service"))
                .when(serviceInstanceService).assertEditAccess(flight);

        assertThatThrownBy(() -> leadService.addProposalItem("L1", line("S1"))).isInstanceOf(AccessDeniedException.class);
        verify(leadProposalRepository, never()).save(any());
    }

    @Test
    void aServiceFromAnotherLeadIsRefused() {
        when(leadServiceRepository.findById("S9")).thenReturn(Optional.of(com.voyra.crm.entity.LeadService.builder()
                .id("S9").leadId("OTHER").type(ServiceType.FLIGHT).build()));

        assertThatThrownBy(() -> leadService.addProposalItem("L1", line("S9"))).isInstanceOf(IllegalArgumentException.class);
        verify(leadProposalRepository, never()).save(any());
    }

    @Test
    void aMarkedUpLinesPriceIsWorkedOutNotTakenFromTheRequest() {
        leadService.addProposalItem("L1", line("S1"));

        ArgumentCaptor<LeadProposal> saved = ArgumentCaptor.forClass(LeadProposal.class);
        verify(leadProposalRepository).save(saved.capture());
        assertThat(saved.getValue().getSellingPrice()).isEqualByComparingTo("1100");
        verify(serviceInstanceService).assertEditAccess(flight);
    }
}
