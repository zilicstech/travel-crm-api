package com.voyra.crm.service;

import com.voyra.crm.dto.AgencyApplicationCreateRequest;
import com.voyra.crm.dto.AgencyApplicationCreatedResponse;
import com.voyra.crm.dto.AgencyApplicationResponse;
import com.voyra.crm.dto.ApplicationDecisionRequest;
import com.voyra.crm.dto.KycDocumentDecisionRequest;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.enums.PlatformAuditAction;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.security.CustomUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
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
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
    private AgencyService agencyService;
    @Mock
    private NotificationService notificationService;
    @Mock
    private PlatformAuditService platformAuditService;
    @InjectMocks
    private AgencyApplicationService service;

    @BeforeEach
    void signInAsPlatformAdmin() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal("root-1", "root", UserType.SUPER_ADMIN, null), null, List.of()));
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    private static AgencyApplication submitted() throws Exception {
        return draft(Instant.now().plusSeconds(60)).toBuilder().status(ApplicationStatus.SUBMITTED).build();
    }

    private static ApplicationDecisionRequest decision(ApplicationStatus status, String rejectionReason) {
        ApplicationDecisionRequest r = new ApplicationDecisionRequest();
        r.setStatus(status);
        r.setNote("checked");
        r.setRejectionReason(rejectionReason);
        return r;
    }

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
                .thenReturn(Optional.of(draft(Instant.now().plusSeconds(60)).toBuilder()
                        .status(ApplicationStatus.SUBMITTED).build()));

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
        when(kycDocumentRepository.countByApplicationIdAndTypeAndStatusNot("app-1", KycDocumentType.PAN, KycDocumentStatus.REJECTED)).thenReturn(0L);

        assertThatThrownBy(() -> service.submit("app-1", TOKEN)).isInstanceOf(IllegalStateException.class)
                .hasMessage("Upload the PAN card first");
        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.DRAFT);
    }

    @Test
    void submitMovesDraftToSubmitted() throws Exception {
        AgencyApplication app = draft(Instant.now().plusSeconds(60));
        when(applicationRepository.findById("app-1")).thenReturn(Optional.of(app));
        when(kycDocumentRepository.countByApplicationIdAndTypeAndStatusNot("app-1", KycDocumentType.PAN, KycDocumentStatus.REJECTED)).thenReturn(1L);

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

    @Test
    void reapplyAfterRejectionResetsTheKeptDocumentsToPending() throws Exception {
        AgencyApplication rejected = draft(Instant.now().minusSeconds(3600)).toBuilder()
                .status(ApplicationStatus.REJECTED).rejectionReason("PAN unreadable").build();
        KycDocument pan = KycDocument.builder().id("doc-1").applicationId("app-1").type(KycDocumentType.PAN)
                .fileKey("k").fileName("pan.pdf").status(KycDocumentStatus.REJECTED).note("Blurry scan")
                .reviewedBy("root-1").reviewedAt(Instant.now()).build();
        KycDocument gst = pan.toBuilder().id("doc-2").type(KycDocumentType.GST).status(KycDocumentStatus.VERIFIED).note(null).build();
        when(applicationRepository.findFirstByOwnerEmailIgnoreCaseAndStatusIn("asha@skytrips.in", OPEN))
                .thenReturn(Optional.empty());
        when(applicationRepository.findFirstByOwnerEmailIgnoreCaseAndStatusIn(eq("asha@skytrips.in"),
                eq(EnumSet.of(ApplicationStatus.REJECTED)))).thenReturn(Optional.of(rejected));
        when(kycDocumentRepository.findByApplicationIdOrderByCreatedAt("app-1")).thenReturn(List.of(pan, gst));

        service.create(request());

        for (KycDocument doc : List.of(pan, gst)) {
            assertThat(doc.getStatus()).isEqualTo(KycDocumentStatus.PENDING);
            assertThat(doc.getNote()).isNull();
            assertThat(doc.getReviewedBy()).isNull();
            assertThat(doc.getReviewedAt()).isNull();
        }
    }

    @Test
    void anExpiredDraftKeepsItsDocumentStates() throws Exception {
        when(applicationRepository.findFirstByOwnerEmailIgnoreCaseAndStatusIn("asha@skytrips.in", OPEN))
                .thenReturn(Optional.of(draft(Instant.now().minusSeconds(60))));

        service.create(request());

        verify(kycDocumentRepository, never()).findByApplicationIdOrderByCreatedAt(anyString());
    }

    @Test
    void createReusesAnExpiredDraft() throws Exception {
        AgencyApplication stale = draft(Instant.now().minusSeconds(60));
        String oldHash = stale.getUploadTokenHash();
        when(applicationRepository.findFirstByOwnerEmailIgnoreCaseAndStatusIn("asha@skytrips.in", OPEN))
                .thenReturn(Optional.of(stale));

        AgencyApplicationCreatedResponse response = service.create(request());

        verify(applicationRepository).save(stale);
        assertThat(response.getId()).isEqualTo("app-1");
        assertThat(stale.getStatus()).isEqualTo(ApplicationStatus.DRAFT);
        assertThat(stale.getUploadTokenHash()).isNotEqualTo(oldHash).isEqualTo(sha256Hex(response.getUploadToken()));
        assertThat(stale.getUploadTokenExpiresAt()).isAfter(Instant.now().plus(Duration.ofHours(23)));
        assertThat(stale.getAgencyName()).isEqualTo("Sky Trips");
    }

    @Test
    void createRefusesAFreshDraftFor24Hours() throws Exception {
        when(applicationRepository.findFirstByOwnerEmailIgnoreCaseAndStatusIn("asha@skytrips.in", OPEN))
                .thenReturn(Optional.of(draft(Instant.now().plusSeconds(3600))));

        assertThatThrownBy(() -> service.create(request())).isInstanceOf(IllegalStateException.class)
                .hasMessage("An application for this email was started in the last 24 hours. "
                        + "Continue it in the same browser or try again after 24 hours.");
        verify(applicationRepository, never()).save(any());
    }

    @Test
    void listCarriesTheDocumentCountWithoutTheDocuments() throws Exception {
        when(applicationRepository.findByStatusInOrderBySubmittedAtDesc(any(), any()))
                .thenReturn(new PageImpl<>(List.of(draft(Instant.now())), PageRequest.of(0, 20), 1));
        when(kycDocumentRepository.countByApplicationId("app-1")).thenReturn(3L);

        var row = service.list(null, PageRequest.of(0, 20)).getContent().get(0);

        assertThat(row.getDocumentCount()).isEqualTo(3L);
        assertThat(row.getDocuments()).isNull();
    }

    @Test
    void attachRefusesThe21stDocument() throws Exception {
        when(applicationRepository.findById("app-1")).thenReturn(Optional.of(draft(Instant.now().plusSeconds(60))));
        when(kycDocumentRepository.countByApplicationId("app-1")).thenReturn(20L);

        assertThatThrownBy(() -> service.attachDocument("app-1", TOKEN, KycDocumentType.OTHER, pdf()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("This application already has the maximum of 20 documents");
        verify(fileStorageService, never()).store(anyString(), anyString(), anyString(), any());
    }

    @Test
    void attachRefusesHtmlNamedPdfWithTheKycMessage() throws Exception {
        when(applicationRepository.findById("app-1")).thenReturn(Optional.of(draft(Instant.now().plusSeconds(60))));
        MockMultipartFile html = new MockMultipartFile("file", "pan.pdf", "application/pdf",
                "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.attachDocument("app-1", TOKEN, KycDocumentType.PAN, html))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Upload a PDF, JPG, PNG or WEBP file");
        verify(fileStorageService, never()).store(anyString(), anyString(), anyString(), any());
    }

    @Test
    void attachRefusesAWordDocument() throws Exception {
        when(applicationRepository.findById("app-1")).thenReturn(Optional.of(draft(Instant.now().plusSeconds(60))));
        MockMultipartFile docx = new MockMultipartFile("file", "pan.docx", "application/vnd.openxmlformats-officedocument",
                new byte[] {'P', 'K', 3, 4, 0});

        assertThatThrownBy(() -> service.attachDocument("app-1", TOKEN, KycDocumentType.PAN, docx))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Upload a PDF, JPG, PNG or WEBP file");
        verify(fileStorageService, never()).store(anyString(), anyString(), anyString(), any());
    }

    @Test
    void attachRefusesASubmittedApplication() throws Exception {
        when(applicationRepository.findById("app-1")).thenReturn(Optional.of(draft(Instant.now().plusSeconds(60))
                .toBuilder().status(ApplicationStatus.SUBMITTED).build()));

        assertThatThrownBy(() -> service.attachDocument("app-1", TOKEN, KycDocumentType.PAN, pdf()))
                .isInstanceOf(IllegalStateException.class);
        verify(fileStorageService, never()).store(anyString(), anyString(), anyString(), any());
    }

    @Test
    void attachShortensAnOverlongFileNameButKeepsItsExtension() throws Exception {
        when(applicationRepository.findById("app-1")).thenReturn(Optional.of(draft(Instant.now().plusSeconds(60))));
        MockMultipartFile file = new MockMultipartFile("file", "p".repeat(300) + ".pdf", "x/" + "y".repeat(120),
                "%PDF-1.4 pan".getBytes(StandardCharsets.UTF_8));
        when(fileStorageService.store(anyString(), anyString(), anyString(), any())).thenReturn("key");

        service.attachDocument("app-1", TOKEN, KycDocumentType.PAN, file);

        ArgumentCaptor<KycDocument> saved = ArgumentCaptor.forClass(KycDocument.class);
        verify(kycDocumentRepository).save(saved.capture());
        assertThat(saved.getValue().getFileName()).hasSize(255).endsWith("p.pdf");
        assertThat(saved.getValue().getContentType()).hasSize(100);
    }

    @Test
    void submitIgnoresARejectedPan() throws Exception {
        AgencyApplication app = draft(Instant.now().plusSeconds(60));
        when(applicationRepository.findById("app-1")).thenReturn(Optional.of(app));
        // The only PAN on file was rejected in an earlier round, so the non-rejected count is 0.
        when(kycDocumentRepository.countByApplicationIdAndTypeAndStatusNot("app-1", KycDocumentType.PAN,
                KycDocumentStatus.REJECTED)).thenReturn(0L);

        assertThatThrownBy(() -> service.submit("app-1", TOKEN)).isInstanceOf(IllegalStateException.class)
                .hasMessage("Upload the PAN card first");
        verify(kycDocumentRepository, never()).countByApplicationIdAndType(anyString(), any());
    }

    @Test
    void decisionRejectRequiresAReason() {
        assertThatThrownBy(() -> service.decide("app-1", decision(ApplicationStatus.REJECTED, " ")))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Give a reason for the rejection");
        verify(applicationRepository, never()).findByIdForUpdate(anyString());
        verify(platformAuditService, never()).record(any(), anyString(), anyString(), any());
    }

    @Test
    void decisionWritesAnAuditRow() throws Exception {
        AgencyApplication app = submitted();
        when(applicationRepository.findByIdForUpdate("app-1")).thenReturn(Optional.of(app));

        AgencyApplicationResponse response = service.decide("app-1", decision(ApplicationStatus.UNDER_REVIEW, null));

        verify(platformAuditService).record(eq(PlatformAuditAction.APPLICATION_DECISION), eq("AGENCY_APPLICATION"), eq("app-1"), any());
        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.UNDER_REVIEW);
        assertThat(app.getReviewedBy()).isEqualTo("root-1");
        assertThat(app.getReviewNote()).isEqualTo("checked");
        assertThat(response.getStatus()).isEqualTo(ApplicationStatus.UNDER_REVIEW);
        assertThat(response.getResumeUrl()).isNull();
    }

    @Test
    void decisionRefusesAnApprovalStatus() {
        assertThatThrownBy(() -> service.decide("app-1", decision(ApplicationStatus.APPROVED, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void decisionOnARejectedApplicationIsRefused() throws Exception {
        when(applicationRepository.findByIdForUpdate("app-1")).thenReturn(Optional.of(submitted().toBuilder()
                .status(ApplicationStatus.REJECTED).build()));

        assertThatThrownBy(() -> service.decide("app-1", decision(ApplicationStatus.UNDER_REVIEW, null)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void requestMoreInfoRotatesTheTokenAndReturnsAResumeLink() throws Exception {
        AgencyApplication app = submitted();
        String oldHash = app.getUploadTokenHash();
        when(applicationRepository.findByIdForUpdate("app-1")).thenReturn(Optional.of(app));

        ApplicationDecisionRequest ask = decision(ApplicationStatus.MORE_INFO, null);
        ask.setNote("  Upload a clearer PAN card ");
        AgencyApplicationResponse response = service.decide("app-1", ask);

        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.MORE_INFO);
        assertThat(app.getReviewNote()).isEqualTo("Upload a clearer PAN card"); // what the email tells the applicant
        assertThat(app.getUploadTokenHash()).isNotEqualTo(oldHash);
        assertThat(app.getUploadTokenExpiresAt()).isCloseTo(Instant.now().plus(Duration.ofDays(7)),
                within(1, java.time.temporal.ChronoUnit.MINUTES));
        assertThat(response.getResumeUrl()).startsWith("/apply?application=app-1&token=");
        String token = response.getResumeUrl().substring("/apply?application=app-1&token=".length());
        assertThat(sha256Hex(token)).isEqualTo(app.getUploadTokenHash());
        verify(notificationService).applicationMoreInfo(app, response.getResumeUrl());
    }

    /** R3: the applicant's email says what is missing, so asking for more information without saying what is refused. */
    @Test
    void requestMoreInfoWithoutANoteIsRefusedAndMailsNothing() throws Exception {
        AgencyApplication app = submitted();

        ApplicationDecisionRequest ask = decision(ApplicationStatus.MORE_INFO, null);
        ask.setNote("   ");
        assertThatThrownBy(() -> service.decide("app-1", ask)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Say what information is needed - the applicant sees this note");
        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.SUBMITTED);
        verify(notificationService, never()).applicationMoreInfo(any(), any());
    }

    @Test
    void documentDecisionUpdatesStatusAndReviewer() throws Exception {
        KycDocument doc = KycDocument.builder().id("doc-1").applicationId("app-1").type(KycDocumentType.PAN)
                .fileKey("k").fileName("pan.pdf").status(KycDocumentStatus.PENDING).build();
        when(applicationRepository.findByIdForUpdate("app-1")).thenReturn(Optional.of(submitted()));
        when(kycDocumentRepository.findByIdAndApplicationId("doc-1", "app-1")).thenReturn(Optional.of(doc));
        KycDocumentDecisionRequest r = new KycDocumentDecisionRequest();
        r.setStatus(KycDocumentStatus.REJECTED);
        r.setNote("Unreadable");

        var response = service.decideDocument("app-1", "doc-1", r);

        assertThat(doc.getStatus()).isEqualTo(KycDocumentStatus.REJECTED);
        assertThat(doc.getNote()).isEqualTo("Unreadable");
        assertThat(doc.getReviewedBy()).isEqualTo("root-1");
        assertThat(doc.getReviewedAt()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(KycDocumentStatus.REJECTED);
        assertThat(response.getNote()).isEqualTo("Unreadable");
        verify(platformAuditService).record(PlatformAuditAction.KYC_DOCUMENT_DECISION, "KYC_DOCUMENT", "doc-1",
                Map.of("applicationId", "app-1", "from", "PENDING", "to", "REJECTED"));
    }

    @Test
    void openDocumentWritesAViewAuditRow() {
        KycDocument doc = KycDocument.builder().id("doc-1").applicationId("app-1").type(KycDocumentType.PAN)
                .fileKey("tenant_platform/kyc/app-1/x.pdf").fileName("pan.pdf").contentType("text/html").build();
        when(kycDocumentRepository.findByIdAndApplicationId("doc-1", "app-1")).thenReturn(Optional.of(doc));
        when(fileStorageService.retrieve("tenant_platform/kyc/app-1/x.pdf")).thenReturn(new ByteArrayResource(new byte[] {1}));

        AgencyApplicationService.KycFileContent content = service.openDocument("app-1", "doc-1");

        assertThat(content.fileName()).isEqualTo("pan.pdf");
        // Served by extension, never the applicant-supplied content type, so an upload can't be rendered as HTML.
        assertThat(content.contentType()).isEqualTo("application/pdf");
        verify(platformAuditService).record(eq(PlatformAuditAction.KYC_DOCUMENT_VIEW), eq("KYC_DOCUMENT"), eq("doc-1"), any());
    }

    @Test
    void approveFromRejectedIsRefused() throws Exception {
        when(applicationRepository.findByIdForUpdate("app-1")).thenReturn(Optional.of(submitted().toBuilder()
                .status(ApplicationStatus.REJECTED).build()));

        assertThatThrownBy(() -> service.approve("app-1", "Secret123")).isInstanceOf(IllegalStateException.class);
        verify(agencyService, never()).provisionFromApplication(any(), anyString());
    }

    @Test
    void approveIsOncePerApplication() throws Exception {
        AgencyApplication app = submitted();
        when(applicationRepository.findByIdForUpdate("app-1")).thenReturn(Optional.of(app));
        when(agencyService.provisionFromApplication(app, "Secret123")).thenReturn(Tenant.builder().id("T-1").build());

        service.approve("app-1", "Secret123");

        assertThatThrownBy(() -> service.approve("app-1", "Secret123")).isInstanceOf(IllegalStateException.class);
        verify(agencyService, times(1)).provisionFromApplication(any(), anyString());
    }

    @Test
    void approveCopiesTheApplicationOntoTheTenant() throws Exception {
        AgencyApplication app = submitted();
        when(applicationRepository.findByIdForUpdate("app-1")).thenReturn(Optional.of(app));
        when(agencyService.provisionFromApplication(any(), eq("Secret123"))).thenReturn(Tenant.builder().id("T-1").build());

        service.approve("app-1", "Secret123");

        ArgumentCaptor<AgencyApplication> provisioned = ArgumentCaptor.forClass(AgencyApplication.class);
        verify(agencyService).provisionFromApplication(provisioned.capture(), eq("Secret123"));
        assertThat(provisioned.getValue()).isSameAs(app);
        assertThat(app.getTenantId()).isEqualTo("T-1");
        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.APPROVED);
        assertThat(app.getUploadTokenExpiresAt()).isBeforeOrEqualTo(Instant.now());
        verify(platformAuditService).record(eq(PlatformAuditAction.APPLICATION_APPROVE), eq("AGENCY_APPLICATION"), eq("app-1"), any());
        verify(notificationService).applicationApproved(app);
    }

    @Test
    void decisionRejectWithAReasonClosesTheApplication() throws Exception {
        AgencyApplication app = submitted();
        when(applicationRepository.findByIdForUpdate("app-1")).thenReturn(Optional.of(app));

        service.decide("app-1", decision(ApplicationStatus.REJECTED, " PAN card is unreadable "));

        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.REJECTED);
        assertThat(app.getRejectionReason()).isEqualTo("PAN card is unreadable");
        // The applicant's upload link dies with the rejection.
        assertThat(app.getUploadTokenExpiresAt()).isBeforeOrEqualTo(Instant.now());
        assertThatThrownBy(() -> service.assertUploadToken(app, TOKEN)).isInstanceOf(AccessDeniedException.class);
        verify(platformAuditService).record(PlatformAuditAction.APPLICATION_DECISION, "AGENCY_APPLICATION", "app-1",
                java.util.Map.of("from", "SUBMITTED", "to", "REJECTED"));
        verify(notificationService).applicationRejected(app);
    }

    @Test
    void reviewableLoadsTheRowForUpdate() throws Exception {
        when(applicationRepository.findByIdForUpdate("app-1")).thenReturn(Optional.of(submitted()));
        when(agencyService.provisionFromApplication(any(), anyString())).thenReturn(Tenant.builder().id("T-1").build());

        service.decide("app-1", decision(ApplicationStatus.UNDER_REVIEW, null));
        service.approve("app-1", "Secret123");

        verify(applicationRepository, times(2)).findByIdForUpdate("app-1");
        verify(applicationRepository, never()).findById(anyString());
    }

    @Test
    void documentDecisionIsRefusedAfterApproval() throws Exception {
        when(applicationRepository.findByIdForUpdate("app-1")).thenReturn(Optional.of(submitted().toBuilder()
                .status(ApplicationStatus.APPROVED).build()));
        KycDocumentDecisionRequest r = new KycDocumentDecisionRequest();
        r.setStatus(KycDocumentStatus.VERIFIED);

        assertThatThrownBy(() -> service.decideDocument("app-1", "doc-1", r)).isInstanceOf(IllegalStateException.class)
                .hasMessage("This application is no longer under review");
        verify(kycDocumentRepository, never()).findByIdAndApplicationId(anyString(), anyString());
    }
}
