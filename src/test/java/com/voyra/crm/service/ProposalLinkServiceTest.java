package com.voyra.crm.service;

import com.voyra.crm.dto.ProposalLinkResponse;
import com.voyra.crm.entity.Lead;
import com.voyra.crm.entity.ProposalLink;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.repository.LeadRepository;
import com.voyra.crm.repository.ProposalLinkRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The public proposal link: reused while its row exists, otherwise a fresh token scoped to the caller's agency. */
@ExtendWith(MockitoExtension.class)
class ProposalLinkServiceTest {

    @Mock private LeadService leadService;
    @Mock private LeadRepository leadRepository;
    @Mock private ProposalLinkRepository proposalLinkRepository;
    @InjectMocks private ProposalLinkService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "publicProposalBaseUrl", "https://app.example.test/p");
        ReflectionTestUtils.setField(service, "validityDays", 30);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal("A1", "agent", UserType.AGENT, "T1"), null, List.of()));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void aLeadWithoutATokenGetsANewLinkForTheCallersAgency() {
        Lead lead = Lead.builder().id("L1").build();
        when(leadService.findAccessibleLead("L1")).thenReturn(lead);

        ProposalLinkResponse out = service.generateLink("L1");

        assertThat(out.getToken()).hasSize(32);
        assertThat(out.getUrl()).isEqualTo("https://app.example.test/p/" + out.getToken());
        assertThat(lead.getPublicProposalToken()).isEqualTo(out.getToken());
        verify(leadRepository).save(lead);
        ArgumentCaptor<ProposalLink> link = ArgumentCaptor.forClass(ProposalLink.class);
        verify(proposalLinkRepository).save(link.capture());
        assertThat(link.getValue().getTenantId()).isEqualTo("T1");
        assertThat(link.getValue().getLeadId()).isEqualTo("L1");
        assertThat(link.getValue().getExpiresAt()).isCloseTo(LocalDateTime.now().plusDays(30), within(1, java.time.temporal.ChronoUnit.MINUTES));
    }

    @Test
    void anExistingLinkIsReturnedUnchanged() {
        Lead lead = Lead.builder().id("L1").publicProposalToken("tok-1").build();
        when(leadService.findAccessibleLead("L1")).thenReturn(lead);
        when(proposalLinkRepository.findById("tok-1")).thenReturn(Optional.of(ProposalLink.builder().token("tok-1").build()));

        ProposalLinkResponse out = service.generateLink("L1");

        assertThat(out.getToken()).isEqualTo("tok-1");
        assertThat(out.getUrl()).isEqualTo("https://app.example.test/p/tok-1");
        verify(proposalLinkRepository, never()).save(any());
        verify(leadRepository, never()).save(any());
    }

    @Test
    void aTokenWhoseLinkRowIsGoneIsReplaced() {
        Lead lead = Lead.builder().id("L1").publicProposalToken("old").build();
        when(leadService.findAccessibleLead("L1")).thenReturn(lead);
        when(proposalLinkRepository.findById("old")).thenReturn(Optional.empty());
        when(proposalLinkRepository.existsById(anyString())).thenReturn(true, false);

        ProposalLinkResponse out = service.generateLink("L1");

        assertThat(out.getToken()).isNotEqualTo("old");
        verify(proposalLinkRepository, org.mockito.Mockito.times(2)).existsById(anyString());
    }

    @Test
    void givesUpAfterTenTakenTokens() {
        when(leadService.findAccessibleLead("L1")).thenReturn(Lead.builder().id("L1").build());
        when(proposalLinkRepository.existsById(anyString())).thenReturn(true);

        assertThatThrownBy(() -> service.generateLink("L1"))
                .isInstanceOf(IllegalStateException.class).hasMessage("Unable to generate unique proposal token after 10 attempts");
        verify(proposalLinkRepository, org.mockito.Mockito.times(10)).existsById(anyString());
        verify(leadRepository, never()).save(any());
    }
}
