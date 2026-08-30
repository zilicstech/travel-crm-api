package com.voyra.crm.service;

import com.voyra.crm.dto.ClientCreateRequest;
import com.voyra.crm.dto.MemberCreateRequest;
import com.voyra.crm.entity.Client;
import com.voyra.crm.entity.Member;
import com.voyra.crm.enums.ClientType;
import com.voyra.crm.enums.MemberRelation;
import com.voyra.crm.enums.MemberType;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.repository.AgentRepository;
import com.voyra.crm.repository.BookingRepository;
import com.voyra.crm.repository.ClientInvoiceRepository;
import com.voyra.crm.repository.ClientRepository;
import com.voyra.crm.repository.LeadMemberRepository;
import com.voyra.crm.repository.LeadRepository;
import com.voyra.crm.repository.MemberDocumentRepository;
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

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Client and roster invariants: one primary member, no duplicate identifiers, agent scoping. */
@ExtendWith(MockitoExtension.class)
class ClientMemberRuleTest {

    @Mock
    private ClientRepository clientRepository;
    @Mock
    private MemberRepository memberRepository;
    @Mock
    private MemberDocumentRepository memberDocumentRepository;
    @Mock
    private LeadMemberRepository leadMemberRepository;
    @Mock
    private LeadRepository leadRepository;
    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private com.voyra.crm.repository.VisaRepository visaRepository;
    @Mock
    private ClientInvoiceRepository clientInvoiceRepository;
    @Mock
    private AgentRepository agentRepository;
    @Mock
    private FileStorageService fileStorageService;
    @Mock
    private MemberMapper memberMapper;

    @InjectMocks
    private ClientService clientService;

    private MemberService memberService() {
        return new MemberService(clientService, memberRepository, memberDocumentRepository,
                leadMemberRepository, fileStorageService, memberMapper);
    }

    private void authenticateAs(String userId, UserType role) {
        CustomUserPrincipal principal = new CustomUserPrincipal(userId, "user", role, "T1");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private Client client(String id, String agentId) {
        return Client.builder().id(id).identifier("9876543210").name("Ajay Sharma")
                .type(ClientType.B2C).agentId(agentId).agentName("Liam Smith").isActive(true).build();
    }

    /**
     * The primary member is created with the client, not as a follow-up call. A client with no
     * primary member has nobody to contact and no source for the name snapshot every downstream
     * table carries.
     */
    @Test
    void creatingAClientAlsoCreatesItsPrimaryMemberAsSelf() {
        authenticateAs("A1", UserType.AGENT);
        when(agentRepository.findById("A1")).thenReturn(Optional.of(
                com.voyra.crm.entity.Agent.builder().id("A1").name("Liam Smith").build()));
        when(clientRepository.existsByIdentifierAndIsActiveTrue("9876543210")).thenReturn(false);
        when(clientRepository.existsById(any())).thenReturn(false);
        when(memberRepository.existsById(any())).thenReturn(false);

        ClientCreateRequest request = new ClientCreateRequest();
        request.setIdentifier("9876543210");
        request.setName("Ajay Sharma");
        request.setType(ClientType.B2C);
        request.setPrimaryMemberName("Ajay Sharma");

        clientService.createClient(request);

        org.mockito.ArgumentCaptor<Member> captor = org.mockito.ArgumentCaptor.forClass(Member.class);
        verify(memberRepository).save(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo(MemberType.CLIENT);
        assertThat(captor.getValue().getRelation()).isEqualTo(MemberRelation.SELF);
    }

    @Test
    void creatingAClientWithAnIdentifierAlreadyInUseIsRejectedAsAConflict() {
        authenticateAs("A1", UserType.AGENT);
        when(agentRepository.findById("A1")).thenReturn(Optional.of(
                com.voyra.crm.entity.Agent.builder().id("A1").name("Liam Smith").build()));
        when(clientRepository.existsByIdentifierAndIsActiveTrue("9876543210")).thenReturn(true);

        ClientCreateRequest request = new ClientCreateRequest();
        request.setIdentifier("9876543210");
        request.setName("Ajay Sharma");
        request.setType(ClientType.B2C);
        request.setPrimaryMemberName("Ajay Sharma");

        assertThatThrownBy(() -> clientService.createClient(request))
                .isInstanceOf(IllegalStateException.class);
        verify(clientRepository, never()).save(any());
    }

    @Test
    void anAgentCannotReadAClientBelongingToAColleague() {
        authenticateAs("A1", UserType.AGENT);
        when(clientRepository.findById("C1")).thenReturn(Optional.of(client("C1", "A2")));

        assertThatThrownBy(() -> clientService.getClient("C1"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void theOwnerCanReadAnyClientInTheAgency() {
        authenticateAs("O1", UserType.AGENCY_OWNER);
        when(clientRepository.findById("C1")).thenReturn(Optional.of(client("C1", "A2")));
        when(memberRepository.findByClientIdAndIsActiveTrue("C1")).thenReturn(List.of());

        assertThat(clientService.getClient("C1").getId()).isEqualTo("C1");
    }

    /** SELF belongs to the primary member alone - handing it out again would break the one-primary rule. */
    @Test
    void addingARosterMemberWithRelationSelfIsRejected() {
        authenticateAs("A1", UserType.AGENT);
        when(clientRepository.findById("C1")).thenReturn(Optional.of(client("C1", "A1")));

        MemberCreateRequest request = new MemberCreateRequest();
        request.setName("Ankita Sharma");
        request.setRelation(MemberRelation.SELF);

        assertThatThrownBy(() -> memberService().addMember("C1", request))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anAgentCannotAddAMemberToAColleaguesClient() {
        authenticateAs("A1", UserType.AGENT);
        when(clientRepository.findById("C1")).thenReturn(Optional.of(client("C1", "A2")));

        MemberCreateRequest request = new MemberCreateRequest();
        request.setName("Ankita Sharma");
        request.setRelation(MemberRelation.SPOUSE);

        assertThatThrownBy(() -> memberService().addMember("C1", request))
                .isInstanceOf(AccessDeniedException.class);
    }

    /**
     * Deactivating the primary member would leave the client with no point of contact, which the
     * partial unique index cannot express as a constraint - so the service rejects it outright.
     */
    @Test
    void deactivatingThePrimaryMemberIsRejected() {
        authenticateAs("A1", UserType.AGENT);
        when(clientRepository.findById("C1")).thenReturn(Optional.of(client("C1", "A1")));
        when(memberRepository.findByMemberIdAndClientId("M1", "C1")).thenReturn(Optional.of(
                Member.builder().memberId("M1").clientId("C1").name("Ajay Sharma")
                        .type(MemberType.CLIENT).relation(MemberRelation.SELF).isActive(true).build()));

        assertThatThrownBy(() -> memberService().updateStatus("C1", "M1", false))
                .isInstanceOf(IllegalStateException.class);
    }

    /** Members are deactivated, never deleted - past lead manifests still reference them. */
    @Test
    void deactivatingARosterMemberKeepsTheRowAndOnlyFlipsTheFlag() {
        authenticateAs("A1", UserType.AGENT);
        when(clientRepository.findById("C1")).thenReturn(Optional.of(client("C1", "A1")));
        Member member = Member.builder().memberId("M2").clientId("C1").name("Ankita Sharma")
                .type(MemberType.MEMBER).relation(MemberRelation.SPOUSE).isActive(true).build();
        when(memberRepository.findByMemberIdAndClientId("M2", "C1")).thenReturn(Optional.of(member));

        memberService().updateStatus("C1", "M2", false);

        verify(memberRepository).save(member);
        verify(memberRepository, never()).delete(any());
        assertThat(member.getIsActive()).isFalse();
    }
}
