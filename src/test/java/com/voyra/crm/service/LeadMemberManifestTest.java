package com.voyra.crm.service;

import com.voyra.crm.dto.LeadMemberAddRequest;
import com.voyra.crm.dto.LeadMemberResponse;
import com.voyra.crm.dto.LeadMemberUpdateRequest;
import com.voyra.crm.entity.Client;
import com.voyra.crm.entity.Lead;
import com.voyra.crm.entity.LeadMember;
import com.voyra.crm.entity.Member;
import com.voyra.crm.enums.ClientType;
import com.voyra.crm.enums.LeadMemberStatus;
import com.voyra.crm.enums.LeadStatus;
import com.voyra.crm.enums.MemberRelation;
import com.voyra.crm.enums.MemberType;
import com.voyra.crm.enums.PaxType;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.repository.AgentRepository;
import com.voyra.crm.repository.LeadMemberRepository;
import com.voyra.crm.repository.LeadNoteRepository;
import com.voyra.crm.repository.LeadProposalRepository;
import com.voyra.crm.repository.LeadServiceRepository;
import com.voyra.crm.repository.LeadRepository;
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

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The traveller manifest: who is on the trip, what state they are in, and what fare class they
 * fly at. The rules here are the ones a bare (lead_id, member_id) link table could not express.
 */
@ExtendWith(MockitoExtension.class)
class LeadMemberManifestTest {

    private static final LocalDate DEPARTURE = LocalDate.of(2026, 9, 15);

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
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private Lead lead() {
        return Lead.builder().id("L1").clientId("C1").clientName("Ajay Sharma").clientType(ClientType.B2C)
                .destination("Dubai, UAE").travelDateFrom(DEPARTURE).travelDateTo(DEPARTURE.plusDays(7))
                .adults(2).kids(0).kidAges(List.of()).totalTravellers(2)
                .status(LeadStatus.NEW).createdBy("A1").createdByName("Liam Smith").build();
    }

    private Member member(String id, String clientId, LocalDate dob) {
        return Member.builder().memberId(id).clientId(clientId).name("Traveller " + id)
                .type(MemberType.MEMBER).relation(MemberRelation.SPOUSE).dob(dob).isActive(true).build();
    }

    /**
     * A member of a different client must never be attachable: the manifest response carries
     * passport numbers, so a cross-client pick would be a data leak between two of the agency's
     * own customers.
     */
    @Test
    void aMemberBelongingToADifferentClientCannotBeAttached() {
        when(leadRepository.findById("L1")).thenReturn(Optional.of(lead()));
        when(memberRepository.findByMemberIdAndClientId("M9", "C1")).thenReturn(Optional.empty());

        LeadMemberAddRequest request = new LeadMemberAddRequest();
        request.setMemberIds(List.of("M9"));

        assertThatThrownBy(() -> leadService.addMembers("L1", request))
                .isInstanceOf(IllegalArgumentException.class);
        verify(leadMemberRepository, never()).save(any());
    }

    @Test
    void addingWithNeitherExistingNorNewTravellersIsRejected() {
        when(leadRepository.findById("L1")).thenReturn(Optional.of(lead()));

        assertThatThrownBy(() -> leadService.addMembers("L1", new LeadMemberAddRequest()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void travellersJoinTheManifestAsTentativeRatherThanConfirmed() {
        when(leadRepository.findById("L1")).thenReturn(Optional.of(lead()));
        when(memberRepository.findByMemberIdAndClientId("M1", "C1"))
                .thenReturn(Optional.of(member("M1", "C1", LocalDate.of(1990, 1, 1))));
        when(leadMemberRepository.existsByLeadIdAndMemberId("L1", "M1")).thenReturn(false);
        when(leadMemberRepository.existsById(any())).thenReturn(false);

        LeadMemberAddRequest request = new LeadMemberAddRequest();
        request.setMemberIds(List.of("M1"));

        List<LeadMemberResponse> added = leadService.addMembers("L1", request);

        assertThat(added).hasSize(1);
        assertThat(added.get(0).getStatus()).isEqualTo(LeadMemberStatus.TENTATIVE);
    }

    @Test
    void attachingTheSameTravellerTwiceIsRejectedAsAConflict() {
        when(leadRepository.findById("L1")).thenReturn(Optional.of(lead()));
        when(memberRepository.findByMemberIdAndClientId("M1", "C1"))
                .thenReturn(Optional.of(member("M1", "C1", LocalDate.of(1990, 1, 1))));
        when(leadMemberRepository.existsByLeadIdAndMemberId("L1", "M1")).thenReturn(true);

        LeadMemberAddRequest request = new LeadMemberAddRequest();
        request.setMemberIds(List.of("M1"));

        assertThatThrownBy(() -> leadService.addMembers("L1", request))
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * The reason lead_members is an entity and not a link table: a traveller who pulls out after
     * their passport was collected must leave a record, not a hole.
     */
    @Test
    void droppingATravellerKeepsTheRowRatherThanDeletingIt() {
        when(leadRepository.findById("L1")).thenReturn(Optional.of(lead()));
        LeadMember row = LeadMember.builder().id("LM1").leadId("L1").memberId("M1").clientId("C1")
                .memberName("Traveller M1").status(LeadMemberStatus.CONFIRMED).build();
        when(leadMemberRepository.findByLeadIdAndMemberId("L1", "M1")).thenReturn(Optional.of(row));
        when(memberRepository.findById("M1")).thenReturn(Optional.of(member("M1", "C1", LocalDate.of(1990, 1, 1))));

        LeadMemberUpdateRequest request = new LeadMemberUpdateRequest();
        request.setStatus(LeadMemberStatus.DROPPED);
        request.setDroppedReason("Visa rejected");

        LeadMemberResponse response = leadService.updateMember("L1", "M1", request);

        assertThat(response.getStatus()).isEqualTo(LeadMemberStatus.DROPPED);
        assertThat(response.getDroppedReason()).isEqualTo("Visa rejected");
        verify(leadMemberRepository, never()).delete(any());
    }

    @Test
    void droppingATravellerWithoutAReasonIsRejected() {
        when(leadRepository.findById("L1")).thenReturn(Optional.of(lead()));
        when(leadMemberRepository.findByLeadIdAndMemberId("L1", "M1")).thenReturn(Optional.of(
                LeadMember.builder().id("LM1").leadId("L1").memberId("M1").clientId("C1")
                        .memberName("Traveller M1").status(LeadMemberStatus.CONFIRMED).build()));

        LeadMemberUpdateRequest request = new LeadMemberUpdateRequest();
        request.setStatus(LeadMemberStatus.DROPPED);

        assertThatThrownBy(() -> leadService.updateMember("L1", "M1", request))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Pax type is resolved against the lead's departure date, not today. */
    @Test
    void paxTypeOnTheManifestIsComputedAgainstTheLeadsDepartureDate() {
        when(leadRepository.findById("L1")).thenReturn(Optional.of(lead()));
        LeadMember row = LeadMember.builder().id("LM1").leadId("L1").memberId("M1").clientId("C1")
                .memberName("Traveller M1").status(LeadMemberStatus.TENTATIVE).build();
        when(leadMemberRepository.findByLeadIdAndMemberId("L1", "M1")).thenReturn(Optional.of(row));
        // Turns 12 exactly one day before departure: a child today, an adult fare on the flight.
        when(memberRepository.findById("M1"))
                .thenReturn(Optional.of(member("M1", "C1", DEPARTURE.minusYears(12).minusDays(1))));

        LeadMemberResponse response = leadService.updateMember("L1", "M1", new LeadMemberUpdateRequest());

        assertThat(response.getPaxType()).isEqualTo(PaxType.ADULT);
        assertThat(response.getAgeAtTravel()).isEqualTo(12);
    }

    @Test
    void aTravellerNotOnTheManifestCannotBeUpdated() {
        when(leadRepository.findById("L1")).thenReturn(Optional.of(lead()));
        when(leadMemberRepository.findByLeadIdAndMemberId("L1", "M9")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> leadService.updateMember("L1", "M9", new LeadMemberUpdateRequest()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** totalTravellers is stored for query speed but must never be trusted from a stale value. */
    @Test
    void totalTravellersIsRecomputedFromAdultsAndKidsOnEveryWrite() {
        Lead stale = lead();
        stale.setAdults(3);
        stale.setKids(2);
        stale.setTotalTravellers(99);
        when(leadRepository.findById("L1")).thenReturn(Optional.of(stale));
        when(memberRepository.findByMemberIdAndClientId("M1", "C1"))
                .thenReturn(Optional.of(member("M1", "C1", LocalDate.of(1990, 1, 1))));
        when(leadMemberRepository.existsByLeadIdAndMemberId("L1", "M1")).thenReturn(false);
        when(leadMemberRepository.existsById(any())).thenReturn(false);

        LeadMemberAddRequest request = new LeadMemberAddRequest();
        request.setMemberIds(List.of("M1"));
        leadService.addMembers("L1", request);

        assertThat(stale.getTotalTravellers()).isEqualTo(5);
    }
}
