package com.voyra.crm.service;

import com.voyra.crm.dto.GuestDetails;
import com.voyra.crm.dto.LeadCreateRequest;
import com.voyra.crm.dto.LeadDetailResponse;
import com.voyra.crm.dto.LeadDetailUpdateRequest;
import com.voyra.crm.dto.LeadMemberAddRequest;
import com.voyra.crm.dto.LeadMemberResponse;
import com.voyra.crm.dto.LeadMemberUpdateRequest;
import com.voyra.crm.dto.LeadNoteCreateRequest;
import com.voyra.crm.dto.LeadNoteResponse;
import com.voyra.crm.dto.LeadResponse;
import com.voyra.crm.dto.MemberCreateRequest;
import com.voyra.crm.dto.PagedResponse;
import com.voyra.crm.dto.ProposalItemBatchCreateRequest;
import com.voyra.crm.dto.ProposalItemBatchLine;
import com.voyra.crm.dto.ProposalItemCreateRequest;
import com.voyra.crm.dto.ProposalItemResponse;
import com.voyra.crm.dto.ProposalItemUpdateRequest;
import com.voyra.crm.dto.ServiceDraft;
import com.voyra.crm.dto.ServiceResponse;
import com.voyra.crm.entity.Agent;
import com.voyra.crm.entity.Client;
import com.voyra.crm.entity.Lead;
import com.voyra.crm.entity.LeadMember;
import com.voyra.crm.entity.LeadNote;
import com.voyra.crm.entity.LeadProposal;
import com.voyra.crm.entity.Member;
import com.voyra.crm.enums.AuditEntityType;
import com.voyra.crm.enums.LeadMemberStatus;
import com.voyra.crm.enums.LeadPriority;
import com.voyra.crm.enums.LeadStatus;
import com.voyra.crm.enums.LeadTimelineEventType;
import com.voyra.crm.enums.MarkupMode;
import com.voyra.crm.enums.MemberRelation;
import com.voyra.crm.enums.MemberType;
import com.voyra.crm.enums.PaxType;
import com.voyra.crm.enums.ProposalItemType;
import com.voyra.crm.enums.ServiceStatus;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Branches of {@link LeadService} the scoping, manifest and proposal tests leave open. */
@ExtendWith(MockitoExtension.class)
class LeadServiceCoverageTest {

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
    @Spy private BaseCurrency baseCurrency = new BaseCurrency(null);
    @InjectMocks private LeadService leadService;

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void as(String userId, UserType role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal(userId, "user", role, "T1"), null, List.of()));
    }

    private static Lead lead(String id) {
        return Lead.builder().id(id).clientId("C1").clientName("Client One").destination("Goa")
                .createdBy("A1").status(LeadStatus.NEW).createdAt(LocalDateTime.of(2026, 1, 1, 10, 0)).build();
    }

    private Lead ownLead() {
        as("O1", UserType.AGENCY_OWNER);
        Lead lead = lead("L1");
        when(leadRepository.findById("L1")).thenReturn(Optional.of(lead));
        return lead;
    }

    // ---------------------------------------------------------------- create

    @Test
    void refusesAReturnBeforeTheTravelDate() {
        LeadCreateRequest r = new LeadCreateRequest();
        r.setTravelDateFrom(LocalDate.of(2026, 5, 10));
        r.setTravelDateTo(LocalDate.of(2026, 5, 9));
        assertThatThrownBy(() -> leadService.createLead(r))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("The return date can't be before the travel date");
        verifyNoInteractions(leadRepository, clientService);
    }

    @Test
    void createDefaultsTheHeadcountSourcePriorityAndDropsBlankNotes() {
        as("O1", UserType.AGENCY_OWNER);
        when(authorResolver.resolveCurrentAuthor()).thenReturn(new AuthorResolver.AuthorInfo("O1", "Owner"));
        when(clientService.findAccessibleClient("C1")).thenReturn(Client.builder().id("C1").name("Client One").build());
        LeadCreateRequest r = new LeadCreateRequest();
        r.setClientId("C1");
        r.setDestination("Goa");
        r.setTravelDateFrom(LocalDate.of(2026, 5, 10));
        r.setSpecialNotes("   ");
        r.setServices(List.of());

        LeadDetailResponse out = leadService.createLead(r);

        ArgumentCaptor<Lead> saved = ArgumentCaptor.forClass(Lead.class);
        verify(leadRepository).save(saved.capture());
        Lead lead = saved.getValue();
        assertThat(lead.getAdults()).isEqualTo(1);
        assertThat(lead.getKids()).isZero();
        assertThat(lead.getTotalTravellers()).isEqualTo(1);
        assertThat(lead.getSource()).isEqualTo("Phone Call");
        assertThat(lead.getPriority()).isEqualTo(LeadPriority.MEDIUM);
        assertThat(lead.getSpecialNotes()).isNull();
        assertThat(lead.getCreatedByName()).isEqualTo("Owner");
        assertThat(out.getGuestDetails().getInfants()).isZero();
        verify(leadTimelineService).recordTransition(lead.getId(), LeadTimelineEventType.LEAD_CREATED, null,
                LeadStatus.NEW, "Lead created for Client One to Goa");
        verify(auditService).recordCreate(AuditEntityType.LEAD, lead.getId(), "Client One / Goa");
        verify(serviceInstanceService, never()).createServices(anyString(), anyList());
    }

    @Test
    void createCountsKidsFromTheirAgesAndCreatesItsServicesInTheSameCall() {
        as("O1", UserType.AGENCY_OWNER);
        when(authorResolver.resolveCurrentAuthor()).thenReturn(new AuthorResolver.AuthorInfo("O1", "Owner"));
        when(clientService.findAccessibleClient("C1")).thenReturn(Client.builder().id("C1").name("Client One").build());
        LeadCreateRequest r = new LeadCreateRequest();
        r.setClientId("C1");
        r.setDestination("Goa");
        r.setTravelDateFrom(LocalDate.of(2026, 5, 10));
        r.setTravelDateTo(LocalDate.of(2026, 5, 12));
        r.setGuestDetails(GuestDetails.builder().adults(2).kidAges(Arrays.asList(1, 7, null)).build());
        r.setSource("Website");
        r.setPriority(LeadPriority.HIGH);
        r.setSpecialNotes("  vegetarian  ");
        ServiceDraft d = new ServiceDraft();
        d.setType(ServiceType.HOTEL);
        r.setServices(List.of(d));

        LeadDetailResponse out = leadService.createLead(r);

        ArgumentCaptor<Lead> saved = ArgumentCaptor.forClass(Lead.class);
        verify(leadRepository).save(saved.capture());
        Lead lead = saved.getValue();
        assertThat(lead.getKids()).isEqualTo(3);
        assertThat(lead.getTotalTravellers()).isEqualTo(5);
        assertThat(lead.getSource()).isEqualTo("Website");
        assertThat(lead.getPriority()).isEqualTo(LeadPriority.HIGH);
        assertThat(lead.getSpecialNotes()).isEqualTo("vegetarian");
        assertThat(out.getGuestDetails().getInfants()).isEqualTo(1);
        verify(serviceInstanceService).createServices(lead.getId(), List.of(d));
    }

    @Test
    void createTakesExplicitKidsOverTheAgeList() {
        as("O1", UserType.AGENCY_OWNER);
        when(authorResolver.resolveCurrentAuthor()).thenReturn(new AuthorResolver.AuthorInfo("O1", "Owner"));
        when(clientService.findAccessibleClient("C1")).thenReturn(Client.builder().id("C1").name("Client One").build());
        LeadCreateRequest r = new LeadCreateRequest();
        r.setClientId("C1");
        r.setTravelDateTo(LocalDate.of(2026, 5, 12));
        r.setGuestDetails(GuestDetails.builder().kids(2).build());

        leadService.createLead(r);

        ArgumentCaptor<Lead> saved = ArgumentCaptor.forClass(Lead.class);
        verify(leadRepository).save(saved.capture());
        assertThat(saved.getValue().getKids()).isEqualTo(2);
        assertThat(saved.getValue().getAdults()).isEqualTo(1);
        assertThat(saved.getValue().getKidAges()).isEmpty();
    }

    // ---------------------------------------------------------------- lists

    @Test
    void ownerListSortsNewestFirstWithUndatedLastAndCountsConfirmedTravellers() {
        as("O1", UserType.AGENCY_OWNER);
        Lead older = lead("L1");
        Lead newer = lead("L2");
        newer.setCreatedAt(LocalDateTime.of(2026, 2, 1, 0, 0));
        Lead undated = lead("L3");
        undated.setCreatedAt(null);
        when(leadRepository.findByStatus(LeadStatus.NEW)).thenReturn(List.of(older, undated, newer));
        when(leadMemberRepository.findByLeadIdIn(List.of("L2", "L1", "L3"))).thenReturn(List.of(
                LeadMember.builder().leadId("L1").status(LeadMemberStatus.CONFIRMED).build(),
                LeadMember.builder().leadId("L1").status(LeadMemberStatus.CONFIRMED).build(),
                LeadMember.builder().leadId("L1").status(LeadMemberStatus.TENTATIVE).build()));
        ServiceResponse s = ServiceResponse.builder().id("S1").leadId("L2").build();
        when(serviceInstanceService.listForLeads(List.of("L2", "L1", "L3"))).thenReturn(Map.of("L2", List.of(s)));

        List<LeadResponse> out = leadService.listLeads(LeadStatus.NEW);

        assertThat(out).extracting(LeadResponse::getId).containsExactly("L2", "L1", "L3");
        assertThat(out.get(1).getConfirmedTravellers()).isEqualTo(2);
        assertThat(out.get(0).getConfirmedTravellers()).isZero();
        assertThat(out.get(0).getServices()).containsExactly(s);
        assertThat(out.get(1).getServices()).isEmpty();
    }

    @Test
    void anAgentWithNoManagedTypesSeesOnlyTheNarrowList() {
        as("A1", UserType.AGENT);
        when(agentRepository.findById("A1")).thenReturn(Optional.empty());
        when(leadRepository.findAccessibleToAgentWithNoManagedTypesAndStatus("A1", LeadStatus.NEW)).thenReturn(List.of());
        when(leadRepository.findAccessibleToAgentWithNoManagedTypes("A1")).thenReturn(List.of());

        assertThat(leadService.listLeads(LeadStatus.NEW)).isEmpty();
        assertThat(leadService.listLeads((LeadStatus) null)).isEmpty();
        verify(leadMemberRepository, never()).findByLeadIdIn(anyList());
    }

    @Test
    void anAgentWithTypesUsesTheTypedQueries() {
        as("A1", UserType.AGENT);
        when(agentRepository.findById("A1")).thenReturn(Optional.of(
                Agent.builder().id("A1").manageableServices(List.of(ServiceType.VISA)).build()));
        Lead l = lead("L1");
        when(leadRepository.findAccessibleToAgentAndStatus("A1", LeadStatus.NEW, List.of(ServiceType.VISA))).thenReturn(List.of(l));
        when(leadRepository.findAccessibleToAgent("A1", List.of(ServiceType.VISA))).thenReturn(List.of());

        assertThat(leadService.listLeads(LeadStatus.NEW)).extracting(LeadResponse::getId).containsExactly("L1");
        assertThat(leadService.listLeads((LeadStatus) null)).isEmpty();
    }

    @Test
    void pagedListPicksTheQueryForEachRoleAndFilter() {
        Pageable p = PageRequest.of(1, 5);
        Pageable sorted = PageRequest.of(1, 5, org.springframework.data.domain.Sort.by(
                org.springframework.data.domain.Sort.Direction.DESC, "createdAt"));
        Lead l = lead("L1");
        l.setFollowUpDate(LocalDate.now().minusDays(1));
        PageImpl<Lead> one = new PageImpl<>(List.of(l), sorted, 6);
        PageImpl<Lead> none = new PageImpl<>(List.of(), sorted, 0);

        as("O1", UserType.AGENCY_OWNER);
        when(leadRepository.findAll(sorted)).thenReturn(one);
        when(leadRepository.findByStatus(LeadStatus.LOST, sorted)).thenReturn(none);
        PagedResponse<LeadResponse> page = leadService.listLeads(null, p);
        assertThat(page.getContent()).extracting(LeadResponse::getId).containsExactly("L1");
        assertThat(page.getContent().get(0).isOverdue()).isTrue();
        assertThat(page.getTotalElements()).isEqualTo(6);
        assertThat(leadService.listLeads(LeadStatus.LOST, p).getContent()).isEmpty();

        as("A1", UserType.AGENT);
        when(agentRepository.findById("A1")).thenReturn(Optional.of(Agent.builder().id("A1").manageableServices(List.of()).build()));
        when(leadRepository.findAccessibleToAgentWithNoManagedTypes("A1", sorted)).thenReturn(none);
        when(leadRepository.findAccessibleToAgentWithNoManagedTypesAndStatus("A1", LeadStatus.NEW, sorted)).thenReturn(none);
        assertThat(leadService.listLeads(null, p).getContent()).isEmpty();
        assertThat(leadService.listLeads(LeadStatus.NEW, p).getContent()).isEmpty();

        as("A2", UserType.AGENT);
        when(agentRepository.findById("A2")).thenReturn(Optional.of(
                Agent.builder().id("A2").manageableServices(List.of(ServiceType.FLIGHT)).build()));
        when(leadRepository.findAccessibleToAgent("A2", List.of(ServiceType.FLIGHT), sorted)).thenReturn(none);
        when(leadRepository.findAccessibleToAgentAndStatus("A2", LeadStatus.NEW, List.of(ServiceType.FLIGHT), sorted)).thenReturn(one);
        assertThat(leadService.listLeads(null, p).getContent()).isEmpty();
        assertThat(leadService.listLeads(LeadStatus.NEW, p).getContent()).hasSize(1);
    }

    // ---------------------------------------------------------------- read / access

    @Test
    void anUnknownLeadIsNotFound() {
        when(leadRepository.findById("NOPE")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> leadService.getLead("NOPE"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Lead not found: NOPE");
    }

    @Test
    void anAgentWithoutCreationOrServiceAccessIsRefused() {
        as("A9", UserType.AGENT);
        when(leadRepository.findById("L1")).thenReturn(Optional.of(lead("L1")));
        when(agentRepository.findById("A9")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> leadService.getLead("L1"))
                .isInstanceOf(AccessDeniedException.class).hasMessage("This lead is not accessible to you");
    }

    @Test
    void detailCarriesThePrimaryContactManifestAndCompleteness() {
        Lead lead = ownLead();
        lead.setTravelDateFrom(LocalDate.of(2026, 6, 1));
        lead.setTravelDateTo(LocalDate.of(2026, 6, 5));
        lead.setTotalTravellers(2);
        lead.setCategories(List.of("FLIGHT", "CRUISE"));
        lead.setPublicProposalToken("tok");
        lead.setKidAges(null);
        when(memberRepository.findByClientIdAndTypeAndIsActiveTrue("C1", MemberType.CLIENT)).thenReturn(Optional.of(
                Member.builder().memberId("M0").email("lead@example.test").countryCode("+91").phone("9000000000").build()));
        when(leadMemberRepository.findByLeadIdOrderByCreatedAtAsc("L1")).thenReturn(List.of(
                LeadMember.builder().id("LM1").memberId("M1").memberName("Pax A").status(LeadMemberStatus.CONFIRMED).build(),
                LeadMember.builder().id("LM2").memberId("M2").memberName("Pax B").status(LeadMemberStatus.TENTATIVE).build(),
                LeadMember.builder().id("LM3").memberId("M3").memberName("Pax C").status(LeadMemberStatus.DROPPED).build()));
        when(memberRepository.findAllById(List.of("M1", "M2", "M3"))).thenReturn(List.of(
                Member.builder().memberId("M1").name("Pax A").dob(LocalDate.of(1990, 1, 1)).gender("F")
                        .relation(MemberRelation.SELF).build(),
                Member.builder().memberId("M2").name("Pax B").dob(LocalDate.of(1992, 1, 1)).gender("M").build()));
        when(leadNoteRepository.findByLeadIdOrderByCreatedAtDesc("L1")).thenReturn(List.of(
                LeadNote.builder().id("N1").authorName("Owner").text("call back").build()));

        LeadDetailResponse out = leadService.getLead("L1");

        assertThat(out.getContactEmail()).isEqualTo("lead@example.test");
        assertThat(out.getContactCountryCode()).isEqualTo("+91");
        assertThat(out.getContactPhone()).isEqualTo("9000000000");
        assertThat(out.isHasPublicProposalLink()).isTrue();
        assertThat(out.getManifestComplete()).isTrue();
        assertThat(out.getNotes()).extracting(LeadNoteResponse::getText).containsExactly("call back");
        assertThat(out.getGuestDetails().getKidAges()).isEmpty();
        LeadMemberResponse dropped = out.getMembers().get(2);
        assertThat(dropped.getPaxType()).isEqualTo(PaxType.UNKNOWN);
        assertThat(dropped.getRelation()).isNull();
        assertThat(dropped.getDocumentsComplete()).isFalse();
        assertThat(dropped.getCrossesPaxBoundary()).isFalse();
        assertThat(out.getMembers().get(0).getPaxType()).isEqualTo(PaxType.ADULT);
        assertThat(out.getMembers().get(0).getRelation()).isEqualTo(MemberRelation.SELF);
    }

    @Test
    void theManifestIsIncompleteWhenTheHeadcountDiffersOrAFieldIsMissing() {
        Lead lead = ownLead();
        lead.setTotalTravellers(1);
        lead.setCategories(List.of("VISA"));
        when(leadMemberRepository.findByLeadIdOrderByCreatedAtAsc("L1")).thenReturn(List.of(
                LeadMember.builder().id("LM1").memberId("M1").memberName("Pax A").status(LeadMemberStatus.CONFIRMED).build()));
        when(memberRepository.findAllById(List.of("M1"))).thenReturn(List.of(
                Member.builder().memberId("M1").name("Pax A").dob(LocalDate.of(1990, 1, 1)).gender("F")
                        .passportNumber("P0000000").passportExpiry(LocalDate.of(2030, 1, 1)).nationality(" ").build()));

        assertThat(leadService.getLead("L1").getManifestComplete()).isFalse();

        lead.setTotalTravellers(2);
        assertThat(leadService.getLead("L1").getManifestComplete()).isFalse();
    }

    @Test
    void documentRulesPerCategory() {
        Lead lead = ownLead();
        lead.setTotalTravellers(1);
        when(leadMemberRepository.findByLeadIdOrderByCreatedAtAsc("L1")).thenReturn(List.of(
                LeadMember.builder().id("LM1").memberId("M1").memberName("Pax A").status(LeadMemberStatus.CONFIRMED).build()));
        Member m = Member.builder().memberId("M1").name("Pax A").build();
        when(memberRepository.findAllById(List.of("M1"))).thenReturn(List.of(m));

        lead.setCategories(null);
        assertThat(leadService.getLead("L1").getManifestComplete()).isTrue();
        lead.setCategories(List.of("HOTEL"));
        assertThat(leadService.getLead("L1").getManifestComplete()).isTrue();
        lead.setCategories(List.of("HOLIDAY_PACKAGE"));
        assertThat(leadService.getLead("L1").getManifestComplete()).isFalse();
        m.setDob(LocalDate.of(1990, 1, 1));
        assertThat(leadService.getLead("L1").getManifestComplete()).isTrue();
        lead.setCategories(List.of("FLIGHT"));
        assertThat(leadService.getLead("L1").getManifestComplete()).isFalse();
        lead.setCategories(List.of("VISA"));
        m.setGender("F");
        assertThat(leadService.getLead("L1").getManifestComplete()).isFalse();
        m.setPassportNumber("P0000000");
        assertThat(leadService.getLead("L1").getManifestComplete()).isFalse();
        m.setPassportExpiry(LocalDate.of(2030, 1, 1));
        assertThat(leadService.getLead("L1").getManifestComplete()).isFalse();
        m.setNationality("Indian");
        assertThat(leadService.getLead("L1").getManifestComplete()).isTrue();
        m.setName(" ");
        assertThat(leadService.getLead("L1").getManifestComplete()).isFalse();
        lead.setCategories(List.of());
        assertThat(leadService.getLead("L1").getManifestComplete()).isFalse();
        m.setName(null);
        lead.setCategories(List.of("HOTEL", "FLIGHT"));
        assertThat(leadService.getLead("L1").getManifestComplete()).isFalse();
    }

    @Test
    void flightAndVisaNeedADateOfBirthAndVisaAGenderAndAChildTurningTwelveIsFlagged() {
        Lead lead = ownLead();
        lead.setTotalTravellers(1);
        lead.setTravelDateFrom(LocalDate.of(2026, 6, 1));
        lead.setTravelDateTo(LocalDate.of(2026, 6, 5));
        when(leadMemberRepository.findByLeadIdOrderByCreatedAtAsc("L1")).thenReturn(List.of(
                LeadMember.builder().id("LM1").memberId("M1").memberName("Pax A").status(LeadMemberStatus.CONFIRMED).build()));
        Member m = Member.builder().memberId("M1").name("Pax A").gender("F").passportNumber("P0000000")
                .passportExpiry(LocalDate.of(2030, 1, 1)).nationality("Indian").build();
        when(memberRepository.findAllById(List.of("M1"))).thenReturn(List.of(m));

        lead.setCategories(List.of("FLIGHT"));
        assertThat(leadService.getLead("L1").getManifestComplete()).isFalse();
        lead.setCategories(List.of("VISA"));
        assertThat(leadService.getLead("L1").getManifestComplete()).isFalse();
        m.setDob(LocalDate.of(2014, 6, 3));
        m.setGender(null);
        LeadDetailResponse out = leadService.getLead("L1");
        assertThat(out.getManifestComplete()).isFalse();
        assertThat(out.getMembers().get(0).getCrossesPaxBoundary()).isTrue();
    }

    @Test
    void aLeadWithNoTravellersIsNotCompleteAndATerminalOrFutureFollowUpIsNotOverdue() {
        Lead lead = ownLead();
        lead.setFollowUpDate(LocalDate.now().minusDays(3));
        lead.setStatus(LeadStatus.BOOKED);
        LeadDetailResponse out = leadService.getLead("L1");
        assertThat(out.getManifestComplete()).isFalse();
        assertThat(out.isOverdue()).isFalse();
        lead.setStatus(LeadStatus.NEW);
        lead.setFollowUpDate(LocalDate.now().plusDays(3));
        assertThat(leadService.getLead("L1").isOverdue()).isFalse();
    }

    // ---------------------------------------------------------------- details

    @Test
    void updateDetailsChangesOnlyTheGivenFields() {
        Lead lead = ownLead();
        lead.setBudget("1L");
        lead.setSpecialNotes("old");
        LeadDetailUpdateRequest r = new LeadDetailUpdateRequest();
        r.setDestination("Kerala");

        leadService.updateDetails("L1", r);
        assertThat(lead.getDestination()).isEqualTo("Kerala");
        assertThat(lead.getBudget()).isEqualTo("1L");
        assertThat(lead.getSpecialNotes()).isEqualTo("old");

        LeadDetailUpdateRequest r2 = new LeadDetailUpdateRequest();
        r2.setBudget("2L");
        r2.setSpecialNotes("new");
        leadService.updateDetails("L1", r2);
        assertThat(lead.getDestination()).isEqualTo("Kerala");
        assertThat(lead.getBudget()).isEqualTo("2L");
        assertThat(lead.getSpecialNotes()).isEqualTo("new");
        assertThat(lead.getUpdatedBy()).isEqualTo("O1");
        verify(leadTimelineService, org.mockito.Mockito.times(2))
                .record("L1", LeadTimelineEventType.DETAILS_UPDATED, "Trip information updated");
        verify(auditService, org.mockito.Mockito.times(2))
                .recordUpdate(eq(AuditEntityType.LEAD), eq("L1"), anyString(), anyList());
    }

    @Test
    void aServiceAgentWhoIsNotAssignedCannotChangeTheTrip() {
        as("A2", UserType.AGENT);
        when(leadRepository.findById("L1")).thenReturn(Optional.of(lead("L1")));
        when(agentRepository.findById("A2")).thenReturn(Optional.of(
                Agent.builder().id("A2").manageableServices(List.of(ServiceType.VISA)).build()));
        when(leadServiceRepository.findByLeadIdOrderBySortOrderAsc("L1")).thenReturn(List.of(
                com.voyra.crm.entity.LeadService.builder().id("S1").leadId("L1").type(ServiceType.VISA).assignedAgentId("A3").build()));

        assertThatThrownBy(() -> leadService.updateDetails("L1", new LeadDetailUpdateRequest()))
                .isInstanceOf(AccessDeniedException.class).hasMessageContaining("can change this trip");
        verify(leadRepository, never()).save(any());
    }

    @Test
    void anAssignedAgentCanChangeTheTrip() {
        as("A2", UserType.AGENT);
        Lead lead = lead("L1");
        when(leadRepository.findById("L1")).thenReturn(Optional.of(lead));
        when(agentRepository.findById("A2")).thenReturn(Optional.of(
                Agent.builder().id("A2").manageableServices(List.of()).build()));
        when(leadServiceRepository.findByLeadIdOrderBySortOrderAsc("L1")).thenReturn(List.of(
                com.voyra.crm.entity.LeadService.builder().id("S1").leadId("L1").type(ServiceType.VISA).assignedAgentId("A2").build()));
        LeadDetailUpdateRequest r = new LeadDetailUpdateRequest();
        r.setDestination("Agra");

        leadService.updateDetails("L1", r);

        assertThat(lead.getDestination()).isEqualTo("Agra");
        verify(leadRepository).save(lead);
    }

    // ---------------------------------------------------------------- manifest

    @Test
    void addMembersNeedsAtLeastOneTraveller() {
        ownLead();
        assertThatThrownBy(() -> leadService.addMembers("L1", new LeadMemberAddRequest()))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Select at least one traveller to add");
    }

    @Test
    void addMembersCreatesAdHocTravellersOnTheLeadsClient() {
        Lead lead = ownLead();
        lead.setTravelDateFrom(LocalDate.of(2026, 6, 1));
        MemberCreateRequest m = new MemberCreateRequest();
        Member created = Member.builder().memberId("M7").name("Pax New").dob(LocalDate.of(2020, 1, 1)).build();
        when(memberService.persistMember("C1", m)).thenReturn(created);
        when(leadMemberRepository.existsByLeadIdAndMemberId("L1", "M7")).thenReturn(false);
        LeadMemberAddRequest r = new LeadMemberAddRequest();
        r.setNewMembers(List.of(m));

        List<LeadMemberResponse> out = leadService.addMembers("L1", r);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).getMemberId()).isEqualTo("M7");
        assertThat(out.get(0).getStatus()).isEqualTo(LeadMemberStatus.TENTATIVE);
        assertThat(out.get(0).getPaxType()).isEqualTo(PaxType.CHILD);
        ArgumentCaptor<LeadMember> row = ArgumentCaptor.forClass(LeadMember.class);
        verify(leadMemberRepository).save(row.capture());
        assertThat(row.getValue().getClientId()).isEqualTo("C1");
        assertThat(row.getValue().getCreatedBy()).isEqualTo("O1");
        verify(leadTimelineService).record("L1", LeadTimelineEventType.MEMBER_ADDED, "Pax New added as a traveller");
    }

    @Test
    void updateMemberWithoutAStatusOnlyStampsTheRowAndAMissingMemberFails() {
        ownLead();
        LeadMember row = LeadMember.builder().id("LM1").leadId("L1").memberId("M1").memberName("Pax A")
                .status(LeadMemberStatus.TENTATIVE).build();
        when(leadMemberRepository.findByLeadIdAndMemberId("L1", "M1")).thenReturn(Optional.of(row));
        when(memberRepository.findById("M1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> leadService.updateMember("L1", "M1", new LeadMemberUpdateRequest()))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Member not found: M1");
        assertThat(row.getStatus()).isEqualTo(LeadMemberStatus.TENTATIVE);
        assertThat(row.getModifiedBy()).isEqualTo("O1");
        verifyNoInteractions(leadTimelineService);
    }

    @Test
    void updateMemberConfirmingClearsADroppedReasonAndUnknownTravellerFails() {
        ownLead();
        LeadMember row = LeadMember.builder().id("LM1").leadId("L1").memberId("M1").memberName("Pax A")
                .status(LeadMemberStatus.DROPPED).droppedReason("ill").build();
        when(leadMemberRepository.findByLeadIdAndMemberId("L1", "M1")).thenReturn(Optional.of(row));
        when(memberRepository.findById("M1")).thenReturn(Optional.of(Member.builder().memberId("M1").name("Pax A").build()));
        LeadMemberUpdateRequest r = new LeadMemberUpdateRequest();
        r.setStatus(LeadMemberStatus.CONFIRMED);

        LeadMemberResponse out = leadService.updateMember("L1", "M1", r);

        assertThat(out.getStatus()).isEqualTo(LeadMemberStatus.CONFIRMED);
        assertThat(row.getDroppedReason()).isNull();
        verify(leadTimelineService).record("L1", LeadTimelineEventType.MEMBER_UPDATED, "Pax A is now CONFIRMED");

        LeadMemberUpdateRequest drop = new LeadMemberUpdateRequest();
        drop.setStatus(LeadMemberStatus.DROPPED);
        drop.setDroppedReason(" ");
        assertThatThrownBy(() -> leadService.updateMember("L1", "M1", drop))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("A reason is required when dropping a traveller");

        when(leadMemberRepository.findByLeadIdAndMemberId("L1", "M9")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> leadService.updateMember("L1", "M9", r))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Traveller not found on this lead: M9");
    }

    @Test
    void listMembersReturnsTheManifest() {
        ownLead();
        when(leadMemberRepository.findByLeadIdOrderByCreatedAtAsc("L1")).thenReturn(List.of(
                LeadMember.builder().id("LM1").memberId("M1").memberName("Pax A").status(LeadMemberStatus.CONFIRMED).build()));
        when(memberRepository.findAllById(List.of("M1"))).thenReturn(List.of(Member.builder().memberId("M1").name("Pax A").build()));
        assertThat(leadService.listMembers("L1")).extracting(LeadMemberResponse::getMemberName).containsExactly("Pax A");
    }

    // ---------------------------------------------------------------- notes

    @Test
    void addNoteSavesTheAuthorAndRecordsTheTimeline() {
        ownLead();
        when(authorResolver.resolveCurrentAuthor()).thenReturn(new AuthorResolver.AuthorInfo("O1", "Owner"));
        LeadNoteCreateRequest r = new LeadNoteCreateRequest();
        r.setText("Prefers aisle");

        LeadNoteResponse out = leadService.addNote("L1", r);

        assertThat(out.getAuthorAgentId()).isEqualTo("O1");
        assertThat(out.getAuthorName()).isEqualTo("Owner");
        assertThat(out.getText()).isEqualTo("Prefers aisle");
        verify(leadNoteRepository).save(any(LeadNote.class));
        verify(leadTimelineService).record("L1", LeadTimelineEventType.NOTE_ADDED, "Note added by Owner");
    }

    // ---------------------------------------------------------------- proposal items

    @Test
    void addingAnItemToALockedProposalIsRefused() {
        Lead lead = ownLead();
        lead.setProposalLocked(true);
        assertThatThrownBy(() -> leadService.addProposalItem("L1", new ProposalItemCreateRequest()))
                .isInstanceOf(IllegalStateException.class).hasMessage("This proposal is locked. Unlock it before making changes.");
        verify(leadProposalRepository, never()).save(any());
    }

    @Test
    void anItemWithNoCostOrPriceIsZeroAndASelectedOptionBecomesTheOnlyOne() {
        Lead lead = ownLead();
        LeadProposal other = LeadProposal.builder().id("P0").leadId("L1").optionGroup("G1").isSelected(true)
                .netCost(new BigDecimal("50")).sellingPrice(new BigDecimal("70")).build();
        LeadProposal elsewhere = LeadProposal.builder().id("PX").leadId("L1").optionGroup("G2").isSelected(true)
                .netCost(new BigDecimal("5")).sellingPrice(new BigDecimal("6")).build();
        List<LeadProposal> lines = new ArrayList<>(List.of(other, elsewhere));
        when(leadProposalRepository.save(any(LeadProposal.class))).thenAnswer(inv -> {
            lines.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(leadProposalRepository.findByLeadId("L1")).thenAnswer(inv -> lines);
        ProposalItemCreateRequest r = new ProposalItemCreateRequest();
        r.setType(ProposalItemType.ACTIVITY);
        r.setDescription("Snorkelling");
        r.setOptionGroup("G1");
        r.setSelected(true);

        ProposalItemResponse out = leadService.addProposalItem("L1", r);

        assertThat(out.getNetCost()).isEqualByComparingTo("0");
        assertThat(out.getSellingPrice()).isEqualByComparingTo("0");
        assertThat(out.isSelected()).isTrue();
        assertThat(out.getSelectedBy()).isEqualTo("AGENT");
        assertThat(other.isSelected()).isFalse();
        assertThat(elsewhere.isSelected()).isTrue();
        // only the selected options count: the new zero line and G2's line
        assertThat(lead.getQuotedNetTotal()).isEqualByComparingTo("5");
        assertThat(lead.getQuotedSellingTotal()).isEqualByComparingTo("6");
        verify(leadTimelineService).record("L1", null, LeadTimelineEventType.PROPOSAL_ITEM_ADDED, "Proposal item added: Snorkelling");
    }

    @Test
    void anOptionNotMarkedSelectedLeavesTheGroupAlone() {
        ownLead();
        ProposalItemCreateRequest r = new ProposalItemCreateRequest();
        r.setOptionGroup("G1");
        r.setNetCost(new BigDecimal("100"));
        r.setMarkupMode(MarkupMode.PERCENT);
        r.setMarkupValue(new BigDecimal("12.5"));

        ProposalItemResponse out = leadService.addProposalItem("L1", r);

        assertThat(out.getSellingPrice()).isEqualByComparingTo("113");
        verify(leadProposalRepository, never()).saveAll(List.of());
        verify(leadProposalRepository, org.mockito.Mockito.times(2)).findByLeadId("L1");
    }

    @Test
    void aLineOnAServiceNeedsThatServiceOnThisLead() {
        ownLead();
        when(leadServiceRepository.findById("S9")).thenReturn(Optional.of(
                com.voyra.crm.entity.LeadService.builder().id("S9").leadId("L2").build()));
        ProposalItemCreateRequest r = new ProposalItemCreateRequest();
        r.setServiceId("S9");
        assertThatThrownBy(() -> leadService.addProposalItem("L1", r))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Service not found on this lead: S9");
    }

    @Test
    void batchAddsEveryOfferUnderTheServiceAsItsOptionGroupInBase() {
        Lead lead = ownLead();
        com.voyra.crm.entity.LeadService s = com.voyra.crm.entity.LeadService.builder().id("S1").leadId("L1")
                .type(ServiceType.FLIGHT).status(ServiceStatus.IN_PROGRESS).label("Flight — BOM–DXB").build();
        when(leadServiceRepository.findById("S1")).thenReturn(Optional.of(s));
        ProposalItemBatchLine usd = new ProposalItemBatchLine();
        usd.setType(ProposalItemType.FLIGHT);
        usd.setDescription("EK 501");
        usd.setPurchaseCurrency("usd");
        usd.setPurchasePrice(new BigDecimal("100"));
        usd.setPurchaseRate(new BigDecimal("83.50"));
        usd.setMarkupMode(MarkupMode.FLAT);
        usd.setMarkupValue(new BigDecimal("500"));
        ProposalItemBatchLine plain = new ProposalItemBatchLine();
        plain.setDescription("AI 983");
        plain.setSellingPrice(new BigDecimal("9000"));
        ProposalItemBatchCreateRequest r = new ProposalItemBatchCreateRequest();
        r.setServiceId("S1");
        r.setItems(List.of(usd, plain));
        when(leadProposalRepository.findAllById(anyList())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<LeadProposal>> c = ArgumentCaptor.forClass(List.class);
            verify(leadProposalRepository).saveAll(c.capture());
            return c.getValue();
        });

        List<ProposalItemResponse> out = leadService.addProposalItemsBatch("L1", r);

        verify(serviceInstanceService).assertEditAccess(s);
        assertThat(out).hasSize(2);
        assertThat(out.get(0).getNetCost()).isEqualByComparingTo("8350.00");
        assertThat(out.get(0).getSellingPrice()).isEqualByComparingTo("8850");
        assertThat(out.get(0).getPurchaseCurrency()).isEqualTo("USD");
        assertThat(out.get(0).getPurchaseRate()).isEqualByComparingTo("83.50");
        assertThat(out.get(0).getOptionGroup()).isEqualTo("S1");
        assertThat(out.get(0).getServiceLabel()).isEqualTo("Flight — BOM–DXB");
        assertThat(out.get(1).getNetCost()).isEqualByComparingTo("0");
        assertThat(out.get(1).getSellingPrice()).isEqualByComparingTo("9000");
        assertThat(out.get(1).getPurchaseCurrency()).isNull();
        verify(leadTimelineService).record("L1", "S1", LeadTimelineEventType.PROPOSAL_ITEM_ADDED,
                "2 proposal option(s) added to Flight — BOM–DXB");
        assertThat(lead.getQuotedNetTotal()).isEqualByComparingTo("0");
    }

    @Test
    void aTripLevelBatchUsesItsOwnGroupAndNamesNoService() {
        ownLead();
        ProposalItemBatchCreateRequest r = new ProposalItemBatchCreateRequest();
        r.setOptionGroup("hotels");
        r.setItems(List.of(new ProposalItemBatchLine()));

        leadService.addProposalItemsBatch("L1", r);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LeadProposal>> c = ArgumentCaptor.forClass(List.class);
        verify(leadProposalRepository).saveAll(c.capture());
        assertThat(c.getValue().get(0).getOptionGroup()).isEqualTo("hotels");
        assertThat(c.getValue().get(0).getServiceLabel()).isNull();
        verify(leadTimelineService).record("L1", null, LeadTimelineEventType.PROPOSAL_ITEM_ADDED, "1 proposal option(s) added");
    }

    @Test
    void batchOnALockedProposalIsRefused() {
        Lead lead = ownLead();
        lead.setProposalLocked(true);
        assertThatThrownBy(() -> leadService.addProposalItemsBatch("L1", new ProposalItemBatchCreateRequest()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void selectingAnOptionMarksItTheAgentsPick() {
        Lead lead = ownLead();
        LeadProposal a = LeadProposal.builder().id("P1").leadId("L1").optionGroup("G").description("Hotel A")
                .netCost(new BigDecimal("10")).sellingPrice(new BigDecimal("12")).build();
        LeadProposal b = LeadProposal.builder().id("P2").leadId("L1").optionGroup("G").isSelected(true)
                .netCost(new BigDecimal("20")).sellingPrice(new BigDecimal("25")).build();
        when(leadProposalRepository.findByIdAndLeadId("P1", "L1")).thenReturn(Optional.of(a));
        when(leadProposalRepository.findByLeadId("L1")).thenReturn(List.of(a, b));

        ProposalItemResponse out = leadService.selectProposalItem("L1", "P1");

        assertThat(out.isSelected()).isTrue();
        assertThat(out.getSelectedBy()).isEqualTo("AGENT");
        assertThat(b.isSelected()).isFalse();
        assertThat(lead.getQuotedNetTotal()).isEqualByComparingTo("10");
        assertThat(lead.getQuotedSellingTotal()).isEqualByComparingTo("12");
        verify(leadTimelineService).record("L1", null, LeadTimelineEventType.PROPOSAL_OPTION_SELECTED, "Selected option: Hotel A");
    }

    @Test
    void selectingANonOptionOrMissingLineFails() {
        Lead lead = ownLead();
        when(leadProposalRepository.findByIdAndLeadId("P1", "L1")).thenReturn(Optional.of(
                LeadProposal.builder().id("P1").leadId("L1").build()));
        when(leadProposalRepository.findByIdAndLeadId("P9", "L1")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> leadService.selectProposalItem("L1", "P1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Only an option line can be selected: P1");
        assertThatThrownBy(() -> leadService.selectProposalItem("L1", "P9"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Proposal item not found: P9");
        lead.setProposalLocked(true);
        assertThatThrownBy(() -> leadService.selectProposalItem("L1", "P1")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void updatingTextAndAFlatMarkupRepricesFromTheCost() {
        ownLead();
        LeadProposal item = LeadProposal.builder().id("P1").leadId("L1").description("Old").supplier("Old Co")
                .netCost(new BigDecimal("1000")).sellingPrice(new BigDecimal("1100")).build();
        when(leadProposalRepository.findByIdAndLeadId("P1", "L1")).thenReturn(Optional.of(item));
        ProposalItemUpdateRequest r = new ProposalItemUpdateRequest();
        r.setDescription("New");
        r.setSupplier("New Co");
        r.setSellingPrice(new BigDecimal("5000"));
        r.setMarkupMode(MarkupMode.FLAT);
        r.setMarkupValue(new BigDecimal("150"));

        ProposalItemResponse out = leadService.updateProposalItem("L1", "P1", r);

        assertThat(out.getDescription()).isEqualTo("New");
        assertThat(out.getSupplier()).isEqualTo("New Co");
        assertThat(out.getMarkupValue()).isEqualByComparingTo("150");
        assertThat(out.getSellingPrice()).isEqualByComparingTo("1150");
        verify(leadProposalRepository).save(item);
        verify(leadTimelineService).record("L1", null, LeadTimelineEventType.PROPOSAL_ITEM_ADDED, "Proposal item updated: New");
    }

    @Test
    void aMarkupModeWithoutAValueKeepsTheTypedPriceAndAMissingCostCountsAsZero() {
        ownLead();
        ProposalItemCreateRequest r = new ProposalItemCreateRequest();
        r.setMarkupMode(MarkupMode.PERCENT);
        r.setSellingPrice(new BigDecimal("777"));
        assertThat(leadService.addProposalItem("L1", r).getSellingPrice()).isEqualByComparingTo("777");

        LeadProposal item = LeadProposal.builder().id("P1").leadId("L1").markupMode(MarkupMode.FLAT)
                .markupValue(new BigDecimal("40")).netCost(null).sellingPrice(null).build();
        when(leadProposalRepository.findByIdAndLeadId("P1", "L1")).thenReturn(Optional.of(item));
        when(leadProposalRepository.findByLeadId("L1")).thenReturn(List.of());
        assertThat(leadService.updateProposalItem("L1", "P1", new ProposalItemUpdateRequest()).getSellingPrice())
                .isEqualByComparingTo("40");
    }

    @Test
    void updatingAPlainPriceKeepsIt() {
        ownLead();
        LeadProposal item = LeadProposal.builder().id("P1").leadId("L1")
                .netCost(new BigDecimal("1000")).sellingPrice(new BigDecimal("1100")).build();
        when(leadProposalRepository.findByIdAndLeadId("P1", "L1")).thenReturn(Optional.of(item));
        ProposalItemUpdateRequest r = new ProposalItemUpdateRequest();
        r.setSellingPrice(new BigDecimal("1300"));

        assertThat(leadService.updateProposalItem("L1", "P1", r).getSellingPrice()).isEqualByComparingTo("1300");
        assertThat(item.getNetCost()).isEqualByComparingTo("1000");
    }

    @Test
    void aForeignCostEditConvertsToBaseAndKeepsTheForeignFigures() {
        ownLead();
        LeadProposal item = LeadProposal.builder().id("P1").leadId("L1").markupMode(MarkupMode.FLAT)
                .markupValue(new BigDecimal("10")).netCost(new BigDecimal("1000")).sellingPrice(new BigDecimal("1010")).build();
        when(leadProposalRepository.findByIdAndLeadId("P1", "L1")).thenReturn(Optional.of(item));
        ProposalItemUpdateRequest r = new ProposalItemUpdateRequest();
        r.setMarkupMode(MarkupMode.FLAT);
        r.setPurchaseCurrency("EUR");
        r.setPurchasePrice(new BigDecimal("10"));
        r.setPurchaseRate(new BigDecimal("90"));
        r.setMarkupCurrency("EUR");
        r.setMarkupInput(new BigDecimal("2"));
        r.setMarkupRate(new BigDecimal("90"));

        ProposalItemResponse out = leadService.updateProposalItem("L1", "P1", r);

        assertThat(out.getNetCost()).isEqualByComparingTo("900.00");
        assertThat(out.getMarkupValue()).isEqualByComparingTo("180.00");
        assertThat(out.getSellingPrice()).isEqualByComparingTo("1080");
        assertThat(out.getPurchaseCurrency()).isEqualTo("EUR");
        assertThat(out.getMarkupCurrency()).isEqualTo("EUR");
        assertThat(out.getMarkupInput()).isEqualByComparingTo("2");
    }

    @Test
    void aBaseCostEditWithoutAMarkupLeavesTheMarkupValue() {
        ownLead();
        LeadProposal item = LeadProposal.builder().id("P1").leadId("L1").markupMode(MarkupMode.PERCENT)
                .markupValue(new BigDecimal("10")).netCost(new BigDecimal("1000")).sellingPrice(new BigDecimal("1100"))
                .purchaseCurrency("USD").purchasePrice(BigDecimal.ONE).purchaseRate(BigDecimal.TEN).build();
        when(leadProposalRepository.findByIdAndLeadId("P1", "L1")).thenReturn(Optional.of(item));
        ProposalItemUpdateRequest r = new ProposalItemUpdateRequest();
        r.setNetCost(new BigDecimal("2000"));

        ProposalItemResponse out = leadService.updateProposalItem("L1", "P1", r);

        assertThat(out.getNetCost()).isEqualByComparingTo("2000");
        assertThat(out.getMarkupValue()).isEqualByComparingTo("10");
        assertThat(out.getSellingPrice()).isEqualByComparingTo("2200");
        assertThat(out.getPurchaseCurrency()).isNull();

        ProposalItemUpdateRequest onlyInput = new ProposalItemUpdateRequest();
        onlyInput.setMarkupInput(new BigDecimal("5"));
        ProposalItemResponse again = leadService.updateProposalItem("L1", "P1", onlyInput);
        assertThat(again.getNetCost()).isEqualByComparingTo("2000");
        assertThat(again.getMarkupValue()).isEqualByComparingTo("10");
    }

    @Test
    void updatingAMissingOrLockedLineFails() {
        Lead lead = ownLead();
        when(leadProposalRepository.findByIdAndLeadId("P9", "L1")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> leadService.updateProposalItem("L1", "P9", new ProposalItemUpdateRequest()))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Proposal item not found: P9");
        lead.setProposalLocked(true);
        assertThatThrownBy(() -> leadService.updateProposalItem("L1", "P9", new ProposalItemUpdateRequest()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void removingALineDeletesItAndRecomputes() {
        Lead lead = ownLead();
        LeadProposal item = LeadProposal.builder().id("P1").leadId("L1").description("Cab").build();
        when(leadProposalRepository.findByIdAndLeadId("P1", "L1")).thenReturn(Optional.of(item));

        leadService.removeProposalItem("L1", "P1");

        verify(leadProposalRepository).delete(item);
        verify(leadRepository).save(lead);
        verify(leadTimelineService).record("L1", null, LeadTimelineEventType.PROPOSAL_ITEM_REMOVED, "Proposal item removed: Cab");

        when(leadProposalRepository.findByIdAndLeadId("P9", "L1")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> leadService.removeProposalItem("L1", "P9"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Proposal item not found: P9");
        lead.setProposalLocked(true);
        assertThatThrownBy(() -> leadService.removeProposalItem("L1", "P1")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void totalsSkipCancelledServicesAndUnpickedOptionsAndRefreshEachServiceSnapshot() {
        Lead lead = lead("L1");
        com.voyra.crm.entity.LeadService live = com.voyra.crm.entity.LeadService.builder().id("S1").leadId("L1")
                .status(ServiceStatus.IN_PROGRESS).build();
        com.voyra.crm.entity.LeadService cancelled = com.voyra.crm.entity.LeadService.builder().id("S2").leadId("L1")
                .status(ServiceStatus.CANCELLED).build();
        when(leadServiceRepository.findByLeadIdOrderBySortOrderAsc("L1")).thenReturn(List.of(live, cancelled));
        when(leadProposalRepository.findByLeadId("L1")).thenReturn(List.of(
                line("S1", null, false, "100", "130"),
                line("S1", "G", true, "200", "260"),
                line("S1", "G", false, "999", "999"),
                line("S2", null, false, "400", "500"),
                line(null, null, false, "10", "11"),
                line("S3", null, false, "1", "2")));

        leadService.recomputeQuotedTotals(lead);

        assertThat(lead.getQuotedNetTotal()).isEqualByComparingTo("311");
        assertThat(lead.getQuotedSellingTotal()).isEqualByComparingTo("403");
        assertThat(live.getNetTotal()).isEqualByComparingTo("300");
        assertThat(live.getSellingTotal()).isEqualByComparingTo("390");
        assertThat(cancelled.getNetTotal()).isEqualByComparingTo("400");
        verify(leadRepository).save(lead);
        verify(leadServiceRepository).saveAll(List.of(live, cancelled));
    }

    private static LeadProposal line(String serviceId, String group, boolean selected, String net, String sell) {
        return LeadProposal.builder().serviceId(serviceId).optionGroup(group).isSelected(selected)
                .netCost(new BigDecimal(net)).sellingPrice(new BigDecimal(sell)).build();
    }

    // ---------------------------------------------------------------- escalation / lock

    @Test
    void escalationNeedsAReasonAndClearingWipesIt() {
        assertThatThrownBy(() -> leadService.setEscalated("L1", true, " "))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("A reason is required when escalating a lead");
        assertThatThrownBy(() -> leadService.setEscalated("L1", true, null))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(leadRepository);

        Lead lead = ownLead();
        LeadDetailResponse up = leadService.setEscalated("L1", true, "VIP client");
        assertThat(up.getEscalated()).isTrue();
        assertThat(lead.getEscalationReason()).isEqualTo("VIP client");
        assertThat(lead.getEscalatedAt()).isNotNull();
        verify(leadTimelineService).record("L1", LeadTimelineEventType.ESCALATED, "Escalated: VIP client");

        leadService.setEscalated("L1", false, null);
        assertThat(lead.getEscalated()).isFalse();
        assertThat(lead.getEscalationReason()).isNull();
        assertThat(lead.getEscalatedAt()).isNull();
        verify(leadTimelineService).record("L1", LeadTimelineEventType.ESCALATION_CLEARED, "Escalation cleared");
    }

    @Test
    void lockingAndUnlockingAProposalTheCustomerNeverConfirmed() {
        Lead lead = ownLead();
        leadService.setProposalLocked("L1", true);
        assertThat(lead.isProposalLocked()).isTrue();
        verify(leadTimelineService).record("L1", LeadTimelineEventType.PROPOSAL_LOCKED, "Proposal locked");

        when(leadProposalRepository.findByLeadId("L1")).thenReturn(List.of(
                LeadProposal.builder().selectedBy("AGENT").build()));
        leadService.setProposalLocked("L1", false);
        assertThat(lead.isProposalLocked()).isFalse();
        verify(leadTimelineService).record("L1", LeadTimelineEventType.PROPOSAL_UNLOCKED, "Proposal unlocked");
    }

    @Test
    void onlyTheOwnerOrCreatorReopensACustomerConfirmedProposal() {
        Lead lead = lead("L1");
        lead.setProposalLocked(true);
        when(leadRepository.findById("L1")).thenReturn(Optional.of(lead));
        when(leadProposalRepository.findByLeadId("L1")).thenReturn(List.of(
                LeadProposal.builder().selectedBy("CUSTOMER").build()));

        as("A2", UserType.AGENT);
        when(agentRepository.findById("A2")).thenReturn(Optional.of(
                Agent.builder().id("A2").manageableServices(List.of()).build()));
        when(leadServiceRepository.findByLeadIdOrderBySortOrderAsc("L1")).thenReturn(List.of(
                com.voyra.crm.entity.LeadService.builder().id("S1").leadId("L1").type(ServiceType.VISA).assignedAgentId("A2").build()));
        assertThatThrownBy(() -> leadService.setProposalLocked("L1", false))
                .isInstanceOf(AccessDeniedException.class).hasMessageContaining("customer confirmed");
        assertThat(lead.isProposalLocked()).isTrue();

        as("A1", UserType.AGENT);
        leadService.setProposalLocked("L1", false);
        assertThat(lead.isProposalLocked()).isFalse();

        lead.setProposalLocked(true);
        as("O1", UserType.AGENCY_OWNER);
        leadService.setProposalLocked("L1", false);
        assertThat(lead.isProposalLocked()).isFalse();
    }
}
