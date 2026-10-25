package com.voyra.crm.scheduler;

import com.voyra.crm.entity.AgencyApplication;
import com.voyra.crm.entity.KycDocument;
import com.voyra.crm.enums.ApplicationStatus;
import com.voyra.crm.repository.AgencyApplicationRepository;
import com.voyra.crm.repository.KycDocumentRepository;
import com.voyra.crm.service.FileStorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgencyApplicationPurgeJobTest {

    @Mock private AgencyApplicationRepository applicationRepository;
    @Mock private KycDocumentRepository kycDocumentRepository;
    @Mock private FileStorageService fileStorageService;
    @InjectMocks private AgencyApplicationPurgeJob job;

    private static KycDocument doc(String id) {
        return KycDocument.builder().id(id).applicationId("app-1").fileKey("tenant_platform/kyc/app-1/" + id).build();
    }

    @Test
    void purgesDocumentsOfRejectedApplicationsOlderThan30Days() {
        AgencyApplication app = AgencyApplication.builder().id("app-1").status(ApplicationStatus.REJECTED)
                .reviewNote("PAN unreadable").build();
        List<KycDocument> docs = List.of(doc("d1"), doc("d2"));
        when(applicationRepository.findByStatusAndUpdatedAtBefore(eq(ApplicationStatus.REJECTED), any()))
                .thenReturn(List.of(app));
        when(kycDocumentRepository.findByApplicationIdOrderByCreatedAt("app-1")).thenReturn(docs);

        job.run();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(applicationRepository).findByStatusAndUpdatedAtBefore(eq(ApplicationStatus.REJECTED), cutoff.capture());
        assertThat(cutoff.getValue()).isCloseTo(Instant.now().minus(Duration.ofDays(30)), within(1, java.time.temporal.ChronoUnit.MINUTES));
        verify(applicationRepository).findByStatusAndUpdatedAtBefore(eq(ApplicationStatus.DRAFT), any());
        verify(fileStorageService).delete("tenant_platform/kyc/app-1/d1");
        verify(fileStorageService).delete("tenant_platform/kyc/app-1/d2");
        verify(kycDocumentRepository).deleteAll(docs);
        verify(applicationRepository).save(app);
        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.REJECTED);
        assertThat(app.getReviewNote()).isEqualTo("PAN unreadable\ndocuments purged");
    }

    @Test
    void leavesRecentRejectionsAlone() {
        // The repository applies the 30-day cutoff, so a recent rejection is never returned.
        job.run();

        verify(fileStorageService, never()).delete(anyString());
        verify(kycDocumentRepository, never()).deleteAll(any());
        verify(applicationRepository, never()).save(any());
    }

    @Test
    void anApplicationWithoutDocumentsIsNotTouchedAgain() {
        AgencyApplication app = AgencyApplication.builder().id("app-1").status(ApplicationStatus.DRAFT).build();
        when(applicationRepository.findByStatusAndUpdatedAtBefore(eq(ApplicationStatus.REJECTED), any())).thenReturn(List.of());
        when(applicationRepository.findByStatusAndUpdatedAtBefore(eq(ApplicationStatus.DRAFT), any())).thenReturn(List.of(app));

        job.run();

        verify(applicationRepository, never()).save(any());
        assertThat(app.getReviewNote()).isNull();
    }

    @Test
    void neverTouchesTheKycFilesOfApprovedApplications() {
        // approval copies no file, so these are the only KYC evidence for a live agency
        job.run();

        verify(applicationRepository, never()).findByStatusAndUpdatedAtBefore(eq(ApplicationStatus.APPROVED), any());
        verify(fileStorageService, never()).delete(anyString());
        verify(kycDocumentRepository, never()).deleteAll(any());
    }
}
