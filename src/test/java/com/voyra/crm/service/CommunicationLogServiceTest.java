package com.voyra.crm.service;

import com.voyra.crm.dto.CommunicationLogCreateRequest;
import com.voyra.crm.dto.CommunicationLogResponse;
import com.voyra.crm.entity.CommunicationLog;
import com.voyra.crm.enums.CommunicationChannel;
import com.voyra.crm.enums.CommunicationDirection;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.repository.CommunicationLogRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommunicationLogServiceTest {

    @Mock CommunicationLogRepository communicationLogRepository;
    @Mock ClientService clientService;
    @Mock AuthorResolver authorResolver;
    @Mock FileStorageService fileStorageService;
    @InjectMocks CommunicationLogService service;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private CommunicationLogCreateRequest request(LocalDateTime occurredAt) {
        CommunicationLogCreateRequest r = new CommunicationLogCreateRequest();
        r.setLeadId("L1");
        r.setMemberId("M1");
        r.setChannel(CommunicationChannel.CALL);
        r.setDirection(CommunicationDirection.OUTBOUND);
        r.setSubject("Visa docs");
        r.setSummary("Asked for passport copies");
        r.setOccurredAt(occurredAt);
        r.setDurationMinutes(5);
        r.setOutcome("Will send tomorrow");
        return r;
    }

    @Test
    void createSavesTheEntryWithTheCurrentAuthor() {
        when(communicationLogRepository.existsById(anyString())).thenReturn(false);
        when(authorResolver.resolveCurrentAuthor()).thenReturn(new AuthorResolver.AuthorInfo("A1", "Agent A"));
        LocalDateTime at = LocalDateTime.of(2026, 10, 1, 10, 0);

        CommunicationLogResponse res = service.create("C1", request(at));

        ArgumentCaptor<CommunicationLog> saved = ArgumentCaptor.forClass(CommunicationLog.class);
        verify(communicationLogRepository).save(saved.capture());
        CommunicationLog e = saved.getValue();
        assertThat(e.getId()).isNotBlank();
        assertThat(e.getClientId()).isEqualTo("C1");
        assertThat(e.getLeadId()).isEqualTo("L1");
        assertThat(e.getMemberId()).isEqualTo("M1");
        assertThat(e.getOccurredAt()).isEqualTo(at);
        assertThat(e.getActorId()).isEqualTo("A1");
        assertThat(e.getActorName()).isEqualTo("Agent A");
        assertThat(e.getCreatedAt()).isNotNull();
        assertThat(res.getId()).isEqualTo(e.getId());
        assertThat(res.getChannel()).isEqualTo(CommunicationChannel.CALL);
        assertThat(res.getDirection()).isEqualTo(CommunicationDirection.OUTBOUND);
        assertThat(res.getSubject()).isEqualTo("Visa docs");
        assertThat(res.getSummary()).isEqualTo("Asked for passport copies");
        assertThat(res.getDurationMinutes()).isEqualTo(5);
        assertThat(res.getOutcome()).isEqualTo("Will send tomorrow");
        assertThat(res.getHasFile()).isFalse();
        assertThat(res.getActorName()).isEqualTo("Agent A");
        verify(clientService).findAccessibleClient("C1");
    }

    @Test
    void createDefaultsOccurredAtToNow() {
        when(communicationLogRepository.existsById(anyString())).thenReturn(false);
        when(authorResolver.resolveCurrentAuthor()).thenReturn(new AuthorResolver.AuthorInfo("A1", "Agent A"));
        LocalDateTime before = LocalDateTime.now();

        CommunicationLogResponse res = service.create("C1", request(null));

        assertThat(res.getOccurredAt()).isAfterOrEqualTo(before);
    }

    @Test
    void listsForClientAfterTheAccessCheckAndForLead() {
        CommunicationLog withFile = CommunicationLog.builder().id("E1").clientId("C1").fileKey("k").fileName("a.pdf").build();
        when(communicationLogRepository.findByClientIdOrderByOccurredAtDesc("C1")).thenReturn(List.of(withFile));
        when(communicationLogRepository.findByLeadIdOrderByOccurredAtDesc("L1")).thenReturn(List.of());

        assertThat(service.listForClient("C1")).singleElement().satisfies(r -> {
            assertThat(r.getId()).isEqualTo("E1");
            assertThat(r.getHasFile()).isTrue();
            assertThat(r.getFileName()).isEqualTo("a.pdf");
        });
        assertThat(service.listForLead("L1")).isEmpty();
        verify(clientService).findAccessibleClient("C1");
    }

    @Test
    void deleteRemovesTheClientsEntry() {
        CommunicationLog entry = CommunicationLog.builder().id("E1").clientId("C1").build();
        when(communicationLogRepository.findByIdAndClientId("E1", "C1")).thenReturn(Optional.of(entry));

        service.delete("C1", "E1");

        verify(communicationLogRepository).delete(entry);
    }

    @Test
    void deleteRefusesAnEntryOfAnotherClient() {
        when(communicationLogRepository.findByIdAndClientId("E1", "C2")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete("C2", "E1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Communication log entry not found: E1");
        verify(communicationLogRepository, never()).delete(any());
    }

    @Test
    void attachFileStoresUnderTheCallersTenant() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal("A1", "agent", UserType.AGENT, "T1"), null, List.of()));
        CommunicationLog entry = CommunicationLog.builder().id("E1").clientId("C1").build();
        when(communicationLogRepository.findByIdAndClientId("E1", "C1")).thenReturn(Optional.of(entry));
        MockMultipartFile file = new MockMultipartFile("file", "note.pdf", "application/pdf", new byte[]{1});
        when(fileStorageService.store("T1", "communication-log", "E1", file)).thenReturn("T1/communication-log/E1/note.pdf");

        CommunicationLogResponse res = service.attachFile("C1", "E1", file);

        assertThat(entry.getFileKey()).isEqualTo("T1/communication-log/E1/note.pdf");
        assertThat(entry.getFileName()).isEqualTo("note.pdf");
        assertThat(entry.getContentType()).isEqualTo("application/pdf");
        verify(communicationLogRepository).save(entry);
        assertThat(res.getHasFile()).isTrue();
    }

    @Test
    void attachFileRefusesAMissingEntry() {
        when(communicationLogRepository.findByIdAndClientId("E1", "C1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.attachFile("C1", "E1", new MockMultipartFile("f", new byte[0])))
                .hasMessage("Communication log entry not found: E1");
        verifyNoInteractions(fileStorageService);
    }

    @Test
    void downloadReturnsTheStoredFile() {
        CommunicationLog entry = CommunicationLog.builder().id("E1").fileKey("k1").fileName("a.pdf").contentType("application/pdf").build();
        when(communicationLogRepository.findByIdAndClientId("E1", "C1")).thenReturn(Optional.of(entry));
        Resource resource = new ByteArrayResource(new byte[]{1});
        when(fileStorageService.retrieve("k1")).thenReturn(resource);

        CommunicationLogService.CommunicationFileContent content = service.downloadFile("C1", "E1");

        assertThat(content.resource()).isSameAs(resource);
        assertThat(content.filename()).isEqualTo("a.pdf");
        assertThat(content.contentType()).isEqualTo("application/pdf");
    }

    @Test
    void downloadRefusesAnEntryWithoutAFileOrAMissingEntry() {
        when(communicationLogRepository.findByIdAndClientId("E1", "C1"))
                .thenReturn(Optional.of(CommunicationLog.builder().id("E1").build()));
        when(communicationLogRepository.findByIdAndClientId("E2", "C1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.downloadFile("C1", "E1")).hasMessage("No file attached to this entry");
        assertThatThrownBy(() -> service.downloadFile("C1", "E2")).hasMessage("Communication log entry not found: E2");
        verifyNoInteractions(fileStorageService);
    }
}
