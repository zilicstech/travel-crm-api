package com.voyra.crm.service;

import com.voyra.crm.dto.CommunicationLogCreateRequest;
import com.voyra.crm.dto.CommunicationLogResponse;
import com.voyra.crm.entity.CommunicationLog;
import com.voyra.crm.repository.CommunicationLogRepository;
import com.voyra.crm.security.SecurityContextUtil;
import com.voyra.crm.util.UniqueIdResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Manually-logged calls, WhatsApp threads, emails and meetings - the internal answer to
 * "communication history" without a real messaging-API integration. Scoped to the client;
 * {@code leadId} on the request ties one entry to a specific enquiry when there is one.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CommunicationLogService {

    private static final String FILE_CATEGORY = "communication-log";

    private final CommunicationLogRepository communicationLogRepository;
    private final ClientService clientService;
    private final AuthorResolver authorResolver;
    private final FileStorageService fileStorageService;

    @Transactional
    public CommunicationLogResponse create(String clientId, CommunicationLogCreateRequest request) {
        clientService.findAccessibleClient(clientId);
        AuthorResolver.AuthorInfo actor = authorResolver.resolveCurrentAuthor();

        CommunicationLog entry = CommunicationLog.builder()
                .id(UniqueIdResolver.resolve(communicationLogRepository::existsById))
                .clientId(clientId)
                .leadId(request.getLeadId())
                .memberId(request.getMemberId())
                .channel(request.getChannel())
                .direction(request.getDirection())
                .subject(request.getSubject())
                .summary(request.getSummary())
                .occurredAt(request.getOccurredAt() != null ? request.getOccurredAt() : LocalDateTime.now())
                .durationMinutes(request.getDurationMinutes())
                .outcome(request.getOutcome())
                .actorId(actor.id())
                .actorName(actor.name())
                .createdAt(LocalDateTime.now())
                .build();
        communicationLogRepository.save(entry);
        log.info("Communication logged: clientId={}, channel={}, id={}", clientId, request.getChannel(), entry.getId());
        return toResponse(entry);
    }

    @Transactional(readOnly = true)
    public List<CommunicationLogResponse> listForClient(String clientId) {
        clientService.findAccessibleClient(clientId);
        return communicationLogRepository.findByClientIdOrderByOccurredAtDesc(clientId).stream()
                .map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<CommunicationLogResponse> listForLead(String leadId) {
        return communicationLogRepository.findByLeadIdOrderByOccurredAtDesc(leadId).stream()
                .map(this::toResponse).toList();
    }

    /** Not {@code @Transactional} - deleting the stored file is object storage work (§8.6). */
    public void delete(String clientId, String id) {
        clientService.findAccessibleClient(clientId);
        CommunicationLog entry = communicationLogRepository.findByIdAndClientId(id, clientId)
                .orElseThrow(() -> new IllegalArgumentException("Communication log entry not found: " + id));
        communicationLogRepository.delete(entry);
        if (entry.getFileKey() != null) {
            fileStorageService.delete(entry.getFileKey());
        }
        log.info("Communication log entry removed: clientId={}, id={}", clientId, id);
    }

    /** Not {@code @Transactional} - see {@link BookingDocumentService#attachFile} for the same §8.6 reasoning. */
    public CommunicationLogResponse attachFile(String clientId, String id, MultipartFile file) {
        clientService.findAccessibleClient(clientId);
        CommunicationLog entry = communicationLogRepository.findByIdAndClientId(id, clientId)
                .orElseThrow(() -> new IllegalArgumentException("Communication log entry not found: " + id));

        String tenantId = SecurityContextUtil.getCurrentUserOrThrow().tenantId();
        String previousFileKey = entry.getFileKey();
        String fileKey = fileStorageService.store(tenantId, FILE_CATEGORY, id, file);

        entry.setFileKey(fileKey);
        entry.setFileName(file.getOriginalFilename());
        entry.setContentType(file.getContentType());
        communicationLogRepository.save(entry);

        if (previousFileKey != null) {
            fileStorageService.delete(previousFileKey);
        }
        log.info("Communication log file attached: clientId={}, id={}", clientId, id);
        return toResponse(entry);
    }

    /** Not {@code @Transactional} - retrieving the file is object storage work (§8.6). */
    public CommunicationFileContent downloadFile(String clientId, String id) {
        clientService.findAccessibleClient(clientId);
        CommunicationLog entry = communicationLogRepository.findByIdAndClientId(id, clientId)
                .orElseThrow(() -> new IllegalArgumentException("Communication log entry not found: " + id));
        if (entry.getFileKey() == null) {
            throw new IllegalArgumentException("No file attached to this entry");
        }
        Resource resource = fileStorageService.retrieve(entry.getFileKey());
        return new CommunicationFileContent(resource, entry.getFileName(), entry.getContentType());
    }

    /** Not a DTO - never serialized to JSON, only unpacked into a binary response by the controller. */
    public record CommunicationFileContent(Resource resource, String filename, String contentType) {
    }

    private CommunicationLogResponse toResponse(CommunicationLog c) {
        return CommunicationLogResponse.builder()
                .id(c.getId()).clientId(c.getClientId()).leadId(c.getLeadId()).memberId(c.getMemberId())
                .channel(c.getChannel()).direction(c.getDirection()).subject(c.getSubject())
                .summary(c.getSummary()).occurredAt(c.getOccurredAt()).durationMinutes(c.getDurationMinutes())
                .outcome(c.getOutcome()).hasFile(c.getFileKey() != null).fileName(c.getFileName())
                .actorName(c.getActorName()).createdAt(c.getCreatedAt())
                .build();
    }
}
