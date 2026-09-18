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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShiftHandoverServiceTest {

    @Mock
    private ShiftHandoverRepository shiftHandoverRepository;
    @Mock
    private AgentRepository agentRepository;
    @Mock
    private AuthorResolver authorResolver;

    private ShiftHandoverService shiftHandoverService;

    private void authenticateAs(String userId, UserType role) {
        CustomUserPrincipal principal = new CustomUserPrincipal(userId, "user", role, "T1");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
        shiftHandoverService = new ShiftHandoverService(shiftHandoverRepository, agentRepository, authorResolver);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void create_resolvesToAgentNameAndStampsActor() {
        authenticateAs("A1", UserType.AGENT);
        when(authorResolver.resolveCurrentAuthor()).thenReturn(new AuthorResolver.AuthorInfo("A1", "Liam Fernandes"));
        when(agentRepository.findById("A2")).thenReturn(Optional.of(Agent.builder().id("A2").name("Maya Rao").build()));
        when(shiftHandoverRepository.existsById(any())).thenReturn(false);

        ShiftHandoverCreateRequest request = new ShiftHandoverCreateRequest();
        request.setToAgentId("A2");
        request.setSummary("Chase the embassy Monday");

        ShiftHandoverResponse response = shiftHandoverService.create(request);

        assertThat(response.getFromAgentId()).isEqualTo("A1");
        assertThat(response.getFromAgentName()).isEqualTo("Liam Fernandes");
        assertThat(response.getToAgentName()).isEqualTo("Maya Rao");

        ArgumentCaptor<ShiftHandover> captor = ArgumentCaptor.forClass(ShiftHandover.class);
        verify(shiftHandoverRepository).save(captor.capture());
        assertThat(captor.getValue().getAcknowledgedAt()).isNull();
    }

    @Test
    void create_wholeTeam_leavesToAgentNull() {
        authenticateAs("O1", UserType.AGENCY_OWNER);
        when(authorResolver.resolveCurrentAuthor()).thenReturn(new AuthorResolver.AuthorInfo("O1", "Owner Name"));
        when(shiftHandoverRepository.existsById(any())).thenReturn(false);

        ShiftHandoverCreateRequest request = new ShiftHandoverCreateRequest();
        request.setSummary("Nothing urgent tonight");

        ShiftHandoverResponse response = shiftHandoverService.create(request);

        assertThat(response.getToAgentId()).isNull();
        assertThat(response.getToAgentName()).isNull();
        verify(agentRepository, org.mockito.Mockito.never()).findById(any());
    }

    @Test
    void agent_listsOwnAndWholeTeamHandovers() {
        authenticateAs("A1", UserType.AGENT);
        when(shiftHandoverRepository.findByToAgentIdOrToAgentIdIsNullOrderByShiftEndedAtDesc("A1"))
                .thenReturn(List.of(ShiftHandover.builder().id("H1").fromAgentId("A2").fromAgentName("X")
                        .summary("s").shiftEndedAt(LocalDateTime.now()).build()));

        List<ShiftHandoverResponse> result = shiftHandoverService.list();

        assertThat(result).hasSize(1);
        verify(shiftHandoverRepository, org.mockito.Mockito.never()).findAllByOrderByShiftEndedAtDesc();
    }

    @Test
    void acknowledge_rejectsWhenAddressedToAnotherAgent() {
        authenticateAs("A1", UserType.AGENT);
        when(shiftHandoverRepository.findById("H1")).thenReturn(Optional.of(
                ShiftHandover.builder().id("H1").toAgentId("A2").fromAgentId("A3").fromAgentName("X")
                        .summary("s").shiftEndedAt(LocalDateTime.now()).build()));

        assertThatThrownBy(() -> shiftHandoverService.acknowledge("H1")).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void acknowledge_isIdempotent() {
        authenticateAs("A2", UserType.AGENT);
        ShiftHandover handover = ShiftHandover.builder().id("H1").toAgentId("A2").fromAgentId("A3").fromAgentName("X")
                .summary("s").shiftEndedAt(LocalDateTime.now())
                .acknowledgedAt(LocalDateTime.of(2026, 9, 1, 9, 0)).acknowledgedBy("A2").acknowledgedByName("Maya Rao")
                .build();
        when(shiftHandoverRepository.findById("H1")).thenReturn(Optional.of(handover));

        ShiftHandoverResponse response = shiftHandoverService.acknowledge("H1");

        assertThat(response.getAcknowledgedAt()).isEqualTo(LocalDateTime.of(2026, 9, 1, 9, 0));
        verify(shiftHandoverRepository, org.mockito.Mockito.never()).save(any());
    }
}
