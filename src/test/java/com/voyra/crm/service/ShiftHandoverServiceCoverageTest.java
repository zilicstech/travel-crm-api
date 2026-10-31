package com.voyra.crm.service;

import com.voyra.crm.dto.ShiftHandoverCreateRequest;
import com.voyra.crm.dto.ShiftHandoverResponse;
import com.voyra.crm.entity.Agent;
import com.voyra.crm.entity.ShiftHandover;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.repository.AgentRepository;
import com.voyra.crm.repository.ShiftHandoverRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
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
class ShiftHandoverServiceCoverageTest {

    @Mock private ShiftHandoverRepository shiftHandoverRepository;
    @Mock private AgentRepository agentRepository;
    @Mock private AuthorResolver authorResolver;
    @InjectMocks private ShiftHandoverService service;

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void as(String id, UserType type) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal(id, "u", type, "T1"), null, List.of()));
    }

    @Test
    void aTeamHandoverHasNoAddresseeAndDefaultsItsListsAndTime() {
        when(authorResolver.resolveCurrentAuthor()).thenReturn(new AuthorResolver.AuthorInfo("A1", "Priya"));
        ShiftHandoverCreateRequest r = new ShiftHandoverCreateRequest();
        r.setToAgentId(" ");
        r.setSummary("Two visas pending");

        ShiftHandoverResponse response = service.create(r);

        assertThat(response.getFromAgentName()).isEqualTo("Priya");
        assertThat(response.getToAgentName()).isNull();
        assertThat(response.getPinnedLeadIds()).isEmpty();
        assertThat(response.getPinnedBookingIds()).isEmpty();
        assertThat(response.getShiftEndedAt()).isNotNull();
        verifyNoInteractions(agentRepository);
    }

    @Test
    void anAddressedHandoverNamesTheAgentAndKeepsItsPins() {
        when(authorResolver.resolveCurrentAuthor()).thenReturn(new AuthorResolver.AuthorInfo("A1", "Priya"));
        when(agentRepository.findById("A2")).thenReturn(Optional.of(Agent.builder().id("A2").name("Vikram").build()));
        LocalDateTime end = LocalDateTime.of(2026, 10, 30, 18, 0);
        ShiftHandoverCreateRequest r = new ShiftHandoverCreateRequest();
        r.setToAgentId("A2");
        r.setPinnedLeadIds(List.of("L1"));
        r.setPinnedBookingIds(List.of("B1"));
        r.setShiftEndedAt(end);

        ShiftHandoverResponse response = service.create(r);

        assertThat(response.getToAgentName()).isEqualTo("Vikram");
        assertThat(response.getPinnedLeadIds()).containsExactly("L1");
        assertThat(response.getPinnedBookingIds()).containsExactly("B1");
        assertThat(response.getShiftEndedAt()).isEqualTo(end);
    }

    @Test
    void anUnknownAddresseeIsRefused() {
        when(authorResolver.resolveCurrentAuthor()).thenReturn(new AuthorResolver.AuthorInfo("A1", "Priya"));
        ShiftHandoverCreateRequest r = new ShiftHandoverCreateRequest();
        r.setToAgentId("A9");
        assertThatThrownBy(() -> service.create(r)).isInstanceOf(IllegalArgumentException.class).hasMessage("Agent not found: A9");
        verify(shiftHandoverRepository, never()).save(any());
    }

    @Test
    void anAgentListsTheirOwnAndTeamHandoversAndAnOwnerAll() {
        as("A1", UserType.AGENT);
        when(shiftHandoverRepository.findVisibleToAgent("A1")).thenReturn(List.of(ShiftHandover.builder().id("H1").build()));
        assertThat(service.list()).extracting(ShiftHandoverResponse::getId).containsExactly("H1");

        as("O1", UserType.AGENCY_OWNER);
        when(shiftHandoverRepository.findAllByOrderByShiftEndedAtDesc()).thenReturn(List.of());
        assertThat(service.list()).isEmpty();
    }

    @Test
    void anAgentMayAcknowledgeATeamHandoverOrOneAddressedToThem() {
        as("A1", UserType.AGENT);
        when(authorResolver.resolveCurrentAuthor()).thenReturn(new AuthorResolver.AuthorInfo("A1", "Priya"));
        ShiftHandover team = ShiftHandover.builder().id("H1").toAgentId(null).build();
        ShiftHandover mine = ShiftHandover.builder().id("H2").toAgentId("A1").build();
        when(shiftHandoverRepository.findById("H1")).thenReturn(Optional.of(team));
        when(shiftHandoverRepository.findById("H2")).thenReturn(Optional.of(mine));

        assertThat(service.acknowledge("H1").getAcknowledgedByName()).isEqualTo("Priya");
        assertThat(service.acknowledge("H2").getAcknowledgedAt()).isNotNull();
        verify(shiftHandoverRepository).save(team);
        verify(shiftHandoverRepository).save(mine);
    }

    @Test
    void someoneElsesHandoverCannotBeAcknowledgedAndASecondAcknowledgementChangesNothing() {
        as("A1", UserType.AGENT);
        when(shiftHandoverRepository.findById("H1")).thenReturn(Optional.of(ShiftHandover.builder().id("H1").toAgentId("A2").build()));
        assertThatThrownBy(() -> service.acknowledge("H1")).isInstanceOf(AccessDeniedException.class).hasMessage("This handover is not addressed to you");

        as("O1", UserType.AGENCY_OWNER);
        LocalDateTime at = LocalDateTime.of(2026, 10, 30, 9, 0);
        when(shiftHandoverRepository.findById("H2")).thenReturn(Optional.of(ShiftHandover.builder().id("H2").toAgentId("A2")
                .acknowledgedAt(at).acknowledgedByName("Vikram").build()));
        assertThat(service.acknowledge("H2").getAcknowledgedAt()).isEqualTo(at);
        assertThatThrownBy(() -> service.acknowledge("H9")).isInstanceOf(IllegalArgumentException.class).hasMessage("Shift handover not found: H9");
        verify(shiftHandoverRepository, never()).save(any());
        verifyNoInteractions(authorResolver);
    }
}
