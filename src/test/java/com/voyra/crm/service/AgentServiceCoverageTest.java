package com.voyra.crm.service;

import com.voyra.crm.cache.AgentCache;
import com.voyra.crm.dto.AgentPerformanceResponse;
import com.voyra.crm.dto.AgentUpdateRequest;
import com.voyra.crm.dto.CredentialsResponse;
import com.voyra.crm.dto.PagedResponse;
import com.voyra.crm.dto.TeammateResponse;
import com.voyra.crm.entity.Agent;
import com.voyra.crm.enums.AgentDepartment;
import com.voyra.crm.enums.ServiceType;
import com.voyra.crm.enums.Sku;
import com.voyra.crm.enums.SubscriptionPlan;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.models.Entitlements;
import com.voyra.crm.repository.AgentRepository;
import com.voyra.crm.repository.BookingRepository;
import com.voyra.crm.repository.LeadNoteRepository;
import com.voyra.crm.repository.LeadRepository;
import com.voyra.crm.repository.SubscriptionRepository;
import com.voyra.crm.repository.VisaRepository;
import com.voyra.crm.security.AesPasswordEncoder;
import com.voyra.crm.security.CustomUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentServiceCoverageTest {

    @Mock private AgentRepository agentRepository;
    @Mock private LeadRepository leadRepository;
    @Mock private LeadNoteRepository leadNoteRepository;
    @Mock private BookingRepository bookingRepository;
    @Mock private VisaRepository visaRepository;
    @Mock private AesPasswordEncoder passwordEncoder;
    @Mock private LoginEmailService loginEmailService;
    @Mock private EntitlementService entitlementService;
    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private com.voyra.crm.repository.TenantRepository tenantRepository;
    @InjectMocks private AgentService service;

    @BeforeEach
    void owner() {
        as("T1");
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        AgentCache.remove("A1");
    }

    private static void as(String tenantId) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal("A1", "u", UserType.AGENCY_OWNER, tenantId), null, List.of()));
    }

    private static Agent.AgentBuilder agent() {
        return Agent.builder().id("A1").tenantId("T1").name("Priya").email("pax-a@example.test").phone("000")
                .userRole(UserType.AGENT).manageableServices(List.of(ServiceType.FLIGHT)).isActive(true)
                .commissionRate(new BigDecimal("10.00"));
    }

    /** A1 with 4 leads (3 booked), 2 active, 1 quotation, 1 pending follow-up, 5 notes, 2 bookings worth 9000 / profit 1200. */
    private void statsForA1() {
        LeadRepository.AgentLeadStatsProjection leads = mock(LeadRepository.AgentLeadStatsProjection.class);
        when(leads.getAgentId()).thenReturn("A1");
        when(leads.getLeadsAssigned()).thenReturn(4L);
        when(leads.getBookedLeads()).thenReturn(3L);
        when(leads.getActiveLeads()).thenReturn(2L);
        when(leads.getQuotationsSent()).thenReturn(1L);
        when(leads.getPendingFollowUps()).thenReturn(1L);
        BookingRepository.AgentBookingStatsProjection bookings = mock(BookingRepository.AgentBookingStatsProjection.class);
        when(bookings.getAgentId()).thenReturn("A1");
        when(bookings.getBookingsCount()).thenReturn(2L);
        when(bookings.getTotalRevenue()).thenReturn(new BigDecimal("9000"));
        when(bookings.getTotalProfit()).thenReturn(new BigDecimal("1200"));
        LeadNoteRepository.AgentNoteCountProjection notes = mock(LeadNoteRepository.AgentNoteCountProjection.class);
        when(notes.getAgentId()).thenReturn("A1");
        when(notes.getNoteCount()).thenReturn(5L);
        when(leadRepository.aggregateLeadStatsByAgent(anyList(), any(), anyCollection(), anyCollection(), any())).thenReturn(List.of(leads));
        when(bookingRepository.aggregateBookingStatsByAgent(anyList())).thenReturn(List.of(bookings));
        when(leadNoteRepository.aggregateNoteCountsByAgent(anyList())).thenReturn(List.of(notes));
    }

    @Test
    void theRosterCarriesEachAgentsPerformanceAndZeroForOneWithNoRows() {
        statsForA1();
        when(agentRepository.findByTenantIdAndUserRole("T1", UserType.AGENT)).thenReturn(List.of(agent().build(), agent().id("A2").build()));

        List<AgentPerformanceResponse> roster = service.listAgents();

        AgentPerformanceResponse a1 = roster.get(0);
        assertThat(a1.getLeadsAssigned()).isEqualTo(4);
        assertThat(a1.getBookedLeads()).isEqualTo(3);
        assertThat(a1.getActiveLeads()).isEqualTo(2);
        assertThat(a1.getQuotationsSent()).isEqualTo(1);
        assertThat(a1.getPendingFollowUps()).isEqualTo(1);
        assertThat(a1.getNotesLogged()).isEqualTo(5);
        assertThat(a1.getBookingsCount()).isEqualTo(2);
        assertThat(a1.getRevenueGenerated()).isEqualByComparingTo("9000");
        assertThat(a1.getProfitGenerated()).isEqualByComparingTo("1200");
        assertThat(a1.getCommission()).isEqualByComparingTo("120.00");
        assertThat(a1.getConversionPercent()).isEqualTo(75);
        AgentPerformanceResponse a2 = roster.get(1);
        assertThat(a2.getLeadsAssigned()).isZero();
        assertThat(a2.getNotesLogged()).isZero();
        assertThat(a2.getRevenueGenerated()).isEqualByComparingTo("0");
        assertThat(a2.getCommission()).isEqualByComparingTo("0");
        assertThat(a2.getConversionPercent()).isZero();
    }

    @Test
    void bookingsWithNoTotalsCountAsZeroMoney() {
        BookingRepository.AgentBookingStatsProjection bookings = mock(BookingRepository.AgentBookingStatsProjection.class);
        when(bookings.getAgentId()).thenReturn("A1");
        when(bookings.getBookingsCount()).thenReturn(1L);
        when(bookings.getTotalRevenue()).thenReturn(null);
        when(bookings.getTotalProfit()).thenReturn(null);
        when(bookingRepository.aggregateBookingStatsByAgent(List.of("A1"))).thenReturn(List.of(bookings));
        when(agentRepository.findById("A1")).thenReturn(Optional.of(agent().build()));

        AgentPerformanceResponse r = service.getAgent("A1");

        assertThat(r.getBookingsCount()).isEqualTo(1);
        assertThat(r.getRevenueGenerated()).isEqualByComparingTo("0");
        assertThat(r.getProfitGenerated()).isEqualByComparingTo("0");
    }

    @Test
    void anEmptyRosterQueriesNoStats() {
        when(agentRepository.findByTenantIdAndUserRole("T1", UserType.ACCOUNTANT)).thenReturn(List.of());
        assertThat(service.listAgents(UserType.ACCOUNTANT)).isEmpty();
        verifyNoInteractions(leadRepository, bookingRepository, leadNoteRepository);
    }

    @Test
    void thePagedRosterKeepsThePage() {
        PageRequest page = PageRequest.of(1, 2);
        when(agentRepository.findByTenantIdAndUserRole("T1", UserType.AGENT, page))
                .thenReturn(new PageImpl<>(List.of(agent().build()), page, 3));

        PagedResponse<AgentPerformanceResponse> r = service.listAgents(page);

        assertThat(r.getContent()).extracting(AgentPerformanceResponse::getId).containsExactly("A1");
        assertThat(r.getTotalElements()).isEqualTo(3);
    }

    @Test
    void aSignedInAgentReadsTheirOwnRecord() {
        when(agentRepository.findById("A1")).thenReturn(Optional.of(agent().build()));
        assertThat(service.getCurrentAgent().getName()).isEqualTo("Priya");
    }

    @Test
    void anAgentOfAnotherAgencyOrAnUnknownOneIsNotYours() {
        when(agentRepository.findById("A1")).thenReturn(Optional.of(agent().tenantId("T2").build()));
        assertThatThrownBy(() -> service.getAgent("A1"))
                .isInstanceOf(AccessDeniedException.class).hasMessage("Agent does not belong to your agency");
        assertThatThrownBy(() -> service.getAgent("A9"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Agent not found: A9");
    }

    @Test
    void aUserWithoutATenantCannotManageAgents() {
        as(null);
        assertThatThrownBy(() -> service.listAgents())
                .isInstanceOf(AccessDeniedException.class).hasMessage("No tenant context for current user");
        verifyNoInteractions(agentRepository);
    }

    @Test
    void teammatesAreTheActiveAgentsWithTheirDepartment() {
        when(agentRepository.findByTenantIdAndUserRole("T1", UserType.AGENT)).thenReturn(List.of(
                agent().department(AgentDepartment.SALES).build(),
                agent().id("A2").name("Vikram").department(null).build(),
                agent().id("A3").isActive(false).build(),
                agent().id("A4").isActive(null).build()));

        List<TeammateResponse> team = service.listTeammates();

        assertThat(team).extracting(TeammateResponse::getId, TeammateResponse::getDepartment)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("A1", "SALES"), org.assertj.core.groups.Tuple.tuple("A2", null));
    }

    @Test
    void renamingAnAgentResyncsEveryNameSnapshot() {
        Agent a = agent().build();
        when(agentRepository.findById("A1")).thenReturn(Optional.of(a));
        AgentUpdateRequest r = new AgentUpdateRequest();
        r.setName("Priya Rao");
        r.setPhone("111");
        r.setCommissionRate(new BigDecimal("7.5"));
        r.setManageableServices(List.of(ServiceType.HOTEL));

        AgentPerformanceResponse response = service.updateAgent("A1", r);

        assertThat(response.getName()).isEqualTo("Priya Rao");
        assertThat(response.getPhone()).isEqualTo("111");
        assertThat(response.getCommissionRate()).isEqualByComparingTo("7.5");
        assertThat(response.getManageableServices()).containsExactly(ServiceType.HOTEL);
        verify(agentRepository).save(a);
        verify(leadRepository).updateCreatedByNameForAgent("A1", "Priya Rao");
        verify(bookingRepository).updateAgentNameForAgent("A1", "Priya Rao");
        verify(leadNoteRepository).updateAuthorNameForAgent("A1", "Priya Rao");
        verify(visaRepository).updateAgentNameForAgent("A1", "Priya Rao");
    }

    @Test
    void anEditWithoutANewNameTouchesNoSnapshot() {
        Agent a = agent().build();
        when(agentRepository.findById("A1")).thenReturn(Optional.of(a));
        AgentUpdateRequest same = new AgentUpdateRequest();
        same.setName("Priya");

        service.updateAgent("A1", same);
        service.updateAgent("A1", new AgentUpdateRequest());

        assertThat(a.getName()).isEqualTo("Priya");
        assertThat(a.getPhone()).isEqualTo("000");
        assertThat(a.getCommissionRate()).isEqualByComparingTo("10.00");
        assertThat(a.getManageableServices()).containsExactly(ServiceType.FLIGHT);
        verify(leadRepository, never()).updateCreatedByNameForAgent(any(), any());
        verifyNoInteractions(visaRepository);
    }

    @Test
    void reactivatingAnAgentWithASeatFreeTurnsTheLoginBackOn() {
        Agent a = agent().isActive(false).build();
        when(agentRepository.findById("A1")).thenReturn(Optional.of(a));
        when(entitlementService.resolve("T1")).thenReturn(new Entitlements(SubscriptionPlan.SUITE, null, EnumSet.allOf(Sku.class), 5, false, null));
        when(agentRepository.countByTenantIdAndIsActiveTrue("T1")).thenReturn(2L);

        assertThat(service.updateStatus("A1", true).getIsActive()).isTrue();

        verify(entitlementService).assertWritable("T1");
        verify(subscriptionRepository).findByTenantIdForUpdate("T1");
        assertThat(AgentCache.isActive("A1")).isTrue();
    }

    @Test
    void credentialsAreDecodedForTheOwner() {
        when(agentRepository.findById("A1")).thenReturn(Optional.of(agent().password("enc").build()));
        when(passwordEncoder.decode("enc")).thenReturn("plain-pw");

        CredentialsResponse c = service.getCredentials("A1");

        assertThat(c.getId()).isEqualTo("A1");
        assertThat(c.getEmail()).isEqualTo("pax-a@example.test");
        assertThat(c.getPassword()).isEqualTo("plain-pw");
    }

    @Test
    void leaderboardAddsOwnerRowWithOwnersBookings() {
        BookingRepository.AgentBookingStatsProjection mine = mock(BookingRepository.AgentBookingStatsProjection.class);
        when(mine.getAgentId()).thenReturn("T1");
        when(mine.getBookingsCount()).thenReturn(3L);
        when(mine.getTotalRevenue()).thenReturn(new BigDecimal("900"));
        when(mine.getTotalProfit()).thenReturn(new BigDecimal("90"));
        when(bookingRepository.aggregateBookingStatsByAgent(List.of("T1"))).thenReturn(List.of(mine));
        when(agentRepository.findByTenantIdAndUserRole("T1", UserType.AGENT)).thenReturn(List.of());
        when(tenantRepository.findById("T1")).thenReturn(Optional.of(
                com.voyra.crm.entity.Tenant.builder().id("T1").ownerName("Olivia Owner").build()));

        List<AgentPerformanceResponse> rows = service.leaderboard();

        assertThat(rows).hasSize(1);
        AgentPerformanceResponse owner = rows.get(0);
        assertThat(owner.getId()).isEqualTo("T1");
        assertThat(owner.getName()).isEqualTo("Olivia Owner");
        assertThat(owner.getBookingsCount()).isEqualTo(3);
        assertThat(owner.getRevenueGenerated()).isEqualByComparingTo("900");
        assertThat(owner.getDepartment()).isNull();
        assertThat(owner.getCommission()).isNull();
        assertThat(owner.getCommissionRate()).isNull();
    }

    @Test
    void leaderboardOwnerWithNoBookingsIsZero() {
        when(agentRepository.findByTenantIdAndUserRole("T1", UserType.AGENT)).thenReturn(List.of());
        when(tenantRepository.findById("T1")).thenReturn(Optional.empty());

        AgentPerformanceResponse owner = service.leaderboard().get(0);

        assertThat(owner.getBookingsCount()).isZero();
        assertThat(owner.getName()).isNull();
    }

    @Test
    void agentListStillExcludesOwner() {
        when(agentRepository.findByTenantIdAndUserRole("T1", UserType.AGENT)).thenReturn(List.of());

        assertThat(service.listAgents()).isEmpty();
        verifyNoInteractions(tenantRepository);
    }
}
