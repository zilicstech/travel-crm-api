package com.voyra.crm.scheduler;

import com.voyra.crm.entity.AgencyApplication;
import com.voyra.crm.entity.KycDocument;
import com.voyra.crm.enums.ApplicationStatus;
import com.voyra.crm.repository.AgencyApplicationRepository;
import com.voyra.crm.repository.KycDocumentRepository;
import com.voyra.crm.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Nightly: KYC files of applications rejected, abandoned as drafts or approved more than 30 days ago are deleted
 * (files and kyc_document rows); the application row stays as the history. Approval copies no file anywhere
 * (the tenant keeps only the typed fields), so nothing else references an approved application's files. Public schema only.
 * Not transactional - object storage work stays outside a transaction (blueprint §8.6).
 * {@code app.applications.purge.enabled} must be false on all but one instance when scaled out.
 */
@Component
@ConditionalOnProperty(name = "app.applications.purge.enabled", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class AgencyApplicationPurgeJob {

    static final Duration RETENTION = Duration.ofDays(30);
    private static final String PURGED = "documents purged";

    private final AgencyApplicationRepository applicationRepository;
    private final KycDocumentRepository kycDocumentRepository;
    private final FileStorageService fileStorageService;

    @Scheduled(cron = "${app.applications.purge.cron:0 30 3 * * *}")
    public void run() {
        Instant cutoff = Instant.now().minus(RETENTION);
        int purged = 0;
        for (ApplicationStatus status : List.of(ApplicationStatus.REJECTED, ApplicationStatus.DRAFT, ApplicationStatus.APPROVED)) {
            for (AgencyApplication app : applicationRepository.findByStatusAndUpdatedAtBefore(status, cutoff)) {
                try {
                    purged += purge(app);
                } catch (Exception e) {
                    log.error("KYC purge failed for application {}: {}", app.getId(), e.getMessage(), e);
                }
            }
        }
        log.info("KYC purge done: {} document(s) deleted", purged);
    }

    private int purge(AgencyApplication app) {
        List<KycDocument> docs = kycDocumentRepository.findByApplicationIdOrderByCreatedAt(app.getId());
        if (docs.isEmpty()) {
            return 0;
        }
        // Files first: if a delete fails the rows survive and the next run retries.
        docs.forEach(d -> fileStorageService.delete(d.getFileKey()));
        kycDocumentRepository.deleteAll(docs);
        app.setReviewNote(app.getReviewNote() == null ? PURGED : app.getReviewNote() + "\n" + PURGED);
        applicationRepository.save(app);
        return docs.size();
    }
}
