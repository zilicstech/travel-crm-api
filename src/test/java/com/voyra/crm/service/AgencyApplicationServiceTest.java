package com.voyra.crm.service;

import com.voyra.crm.dto.AgencyApplicationCreateRequest;
import com.voyra.crm.dto.AgencyApplicationCreatedResponse;
import com.voyra.crm.entity.AgencyApplication;
import com.voyra.crm.entity.KycDocument;
import com.voyra.crm.enums.ApplicationStatus;
import com.voyra.crm.enums.KycDocumentStatus;
import com.voyra.crm.enums.KycDocumentType;
import com.voyra.crm.repository.AgencyApplicationRepository;
import com.voyra.crm.repository.KycDocumentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgencyApplicationServiceTest {

    private static final EnumSet<ApplicationStatus> OPEN = EnumSet.of(ApplicationStatus.DRAFT,
            ApplicationStatus.SUBMITTED, ApplicationStatus.UNDER_REVIEW, ApplicationStatus.MORE_INFO);
    private static final String TOKEN = "the-raw-upload-token-of-32-chars-or-more";

    @Mock
    private AgencyApplicationRepository applicationRepository;
    @Mock
    private KycDocumentRepository kycDocumentRepository;
    @Mock
    private FileStorageService fileStorageService;
    @Mock
    private LoginEmailService loginEmailService;
    @Mock
    private PlatformAuditService platformAuditService;
    @InjectMocks
    private AgencyApplicationService service;

    private static String sha256Hex(String s) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    }

    private static AgencyApplicationCreateRequest request() {
        AgencyApplicationCreateRequest r = new AgencyApplicationCreateRequest();
        r.setAgencyName(" Sky Trips ");
        r.setPan("ABCDE1234F");
        r.setOwnerName("Asha Rao");
        r.setOwnerEmail("asha@skytrips.in");
        r.setOwnerPhone("+91 98765 43210");
        r.setConsent(true);
        return r;
    }

    private static AgencyApplication draft(Instant expiresAt) throws Exception {
        return AgencyApplication.builder().id("app-1").status(ApplicationStatus.DRAFT)
                .ownerEmail("asha@skytrips.in").uploadTokenHash(sha256Hex(TOKEN)).uploadTokenExpiresAt(expiresAt).build();
    }

    private static MockMultipartFile pdf() {
        return new MockMultipartFile("file", "pan.pdf", "application/pdf", "%PDF-1.4 pan".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void createRefusesAnEmailAlreadyUsedByALogin() {
        doThrow(new IllegalArgumentException("This email is already used by another login - use a different email"))
                .when(loginEmailService).assertFree("asha@skytrips.in");

        assertThatThrownBy(() -> service.create(request())).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already used by another login");
        verify(applicationRepository, never()).save(any());
    }

    @Test
    void createRefusesAnEmailWithAnOpenApplication() throws Exception {
        when(applicationRepository.findFirstByOwnerEmailIgnoreCaseAndStatusIn("asha@skytrips.in", OPEN))
                .thenReturn(Optional.of(draft(Instant.now().plusSeconds(60))));

        assertThatThrownBy(() -> service.create(request())).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("An application for this email is already under review");
        verify(applicationRepository, never()).save(any());
    }

    @Test
    void createReturnsARawTokenAndStoresOnlyItsHash() throws Exception {
        AgencyApplicationCreatedResponse response = service.create(request());

        ArgumentCaptor<AgencyApplication> saved = ArgumentCaptor.forClass(AgencyApplication.class);
        verify(applicationRepository).save(saved.capture());
        AgencyApplication app = saved.getValue();
        assertThat(response.getId()).isEqualTo(app.getId()).hasSize(36);
        assertThat(response.getUploadToken()).hasSizeGreaterThanOrEqualTo(32);
        assertThat(app.getUploadTokenHash()).isEqualTo(sha256Hex(response.getUploadToken()));
        assertThat(app.getUploadTokenExpiresAt()).isCloseTo(Instant.now().plus(Duration.ofHours(24)), within(1, java.time.temporal.ChronoUnit.MINUTES));
        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.DRAFT);
        assertThat(app.getAgencyName()).isEqualTo("Sky Trips");
        assertThat(app.getBaseCurrencyCode()).isEqualTo("INR");
        assertThat(app.getConsentAt()).isNotNull();
    }

    @Test
    void honeypotFilledReturnsAFakeSuccessWithoutSaving() {
        AgencyApplicationCreateRequest r = request();
        r.setWebsite("http://spam");

        AgencyApplicationCreatedResponse response = service.create(r);

        assertThat(response.getId()).hasSize(36);
        assertThat(response.getUploadToken()).hasSizeGreaterThanOrEqualTo(32);
        verify(applicationRepository, never()).save(any());
        verify(loginEmailService, never()).assertFree(anyString());
    }

    @Test
    void attachRefusesAWrongToken() throws Exception {
        when(applicationRepository.findById("app-1")).thenReturn(Optional.of(draft(Instant.now().plusSeconds(60))));

        assertThatThrownBy(() -> service.attachDocument("app-1", "wrong-token", KycDocumentType.PAN, pdf()))
                .isInstanceOf(AccessDeniedException.class);
        verify(fileStorageService, never()).store(anyString(), anyString(), anyString(), any());
    }

    @Test
    void attachRefusesAnExpiredToken() throws Exception {
        when(applicationRepository.findById("app-1")).thenReturn(Optional.of(draft(Instant.now().minusSeconds(1))));

        assertThatThrownBy(() -> service.attachDocument("app-1", TOKEN, KycDocumentType.PAN, pdf()))
                .isInstanceOf(AccessDeniedException.class);
        verify(fileStorageService, never()).store(anyString(), anyString(), anyString(), any());
    }

    @Test
    void attachRefusesAFileOver5Mb() throws Exception {
        when(applicationRepository.findById("app-1")).thenReturn(Optional.of(draft(Instant.now().plusSeconds(60))));
        MockMultipartFile big = new MockMultipartFile("file", "pan.pdf", "application/pdf", new byte[5 * 1024 * 1024 + 1]);

        assertThatThrownBy(() -> service.attachDocument("app-1", TOKEN, KycDocumentType.PAN, big))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("File is larger than 5 MB");
        verify(fileStorageService, never()).store(anyString(), anyString(), anyString(), any());
    }

    @Test
    void attachStoresUnderThePlatformScope() throws Exception {
        when(applicationRepository.findById("app-1")).thenReturn(Optional.of(draft(Instant.now().plusSeconds(60))));
        MockMultipartFile file = pdf();
        when(fileStorageService.store("platform", "kyc", "app-1", file)).thenReturn("tenant_platform/kyc/app-1/x.pdf");

        var response = service.attachDocument("app-1", TOKEN, KycDocumentType.PAN, file);

        verify(fileStorageService).store("platform", "kyc", "app-1", file);
        ArgumentCaptor<KycDocument> saved = ArgumentCaptor.forClass(KycDocument.class);
        verify(kycDocumentRepository).save(saved.capture());
        KycDocument doc = saved.getValue();
        assertThat(doc.getApplicationId()).isEqualTo("app-1");
        assertThat(doc.getFileKey()).isEqualTo("tenant_platform/kyc/app-1/x.pdf");
        assertThat(doc.getStatus()).isEqualTo(KycDocumentStatus.PENDING);
        assertThat(response.getId()).isEqualTo(doc.getId());
        assertThat(response.getType()).isEqualTo(KycDocumentType.PAN);
        assertThat(response.getFileName()).isEqualTo("pan.pdf");
        assertThat(response.getSizeBytes()).isEqualTo(file.getSize());
        assertThat(response.getStatus()).isEqualTo(KycDocumentStatus.PENDING);
    }

    @Test
    void submitNeedsAPanDocument() throws Exception {
        AgencyApplication app = draft(Instant.now().plusSeconds(60));
        when(applicationRepository.findById("app-1")).thenReturn(Optional.of(app));
        when(kycDocumentRepository.countByApplicationIdAndType("app-1", KycDocumentType.PAN)).thenReturn(0L);

        assertThatThrownBy(() -> service.submit("app-1", TOKEN)).isInstanceOf(IllegalStateException.class)
                .hasMessage("Upload the PAN card first");
        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.DRAFT);
    }

    @Test
    void submitMovesDraftToSubmitted() throws Exception {
        AgencyApplication app = draft(Instant.now().plusSeconds(60));
        when(applicationRepository.findById("app-1")).thenReturn(Optional.of(app));
        when(kycDocumentRepository.countByApplicationIdAndType("app-1", KycDocumentType.PAN)).thenReturn(1L);

        service.submit("app-1", TOKEN);

        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.SUBMITTED);
        assertThat(app.getSubmittedAt()).isCloseTo(Instant.now(), within(1, java.time.temporal.ChronoUnit.MINUTES));
    }

    @Test
    void reapplyAfterRejectionReusesTheRow() throws Exception {
        AgencyApplication rejected = draft(Instant.now().minusSeconds(3600)).toBuilder()
                .status(ApplicationStatus.REJECTED).rejectionReason("PAN unreadable").reviewNote("blurred").build();
        String oldHash = rejected.getUploadTokenHash();
        when(applicationRepository.findFirstByOwnerEmailIgnoreCaseAndStatusIn("asha@skytrips.in", OPEN))
                .thenReturn(Optional.empty());
        when(applicationRepository.findFirstByOwnerEmailIgnoreCaseAndStatusIn(eq("asha@skytrips.in"),
                eq(EnumSet.of(ApplicationStatus.REJECTED)))).thenReturn(Optional.of(rejected));

        AgencyApplicationCreatedResponse response = service.create(request());

        verify(applicationRepository).save(rejected);
        assertThat(response.getId()).isEqualTo("app-1");
        assertThat(rejected.getStatus()).isEqualTo(ApplicationStatus.DRAFT);
        assertThat(rejected.getUploadTokenHash()).isNotEqualTo(oldHash).isEqualTo(sha256Hex(response.getUploadToken()));
        assertThat(rejected.getRejectionReason()).isNull();
        assertThat(rejected.getAgencyName()).isEqualTo("Sky Trips");
    }
}
