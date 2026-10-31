package com.voyra.crm.service;

import com.voyra.crm.dto.AgencyApplicationCreateRequest;
import com.voyra.crm.dto.AgencyApplicationResponse;
import com.voyra.crm.dto.ApplicationDecisionRequest;
import com.voyra.crm.dto.KycDocumentDecisionRequest;
import com.voyra.crm.dto.KycDocumentResponse;
import com.voyra.crm.entity.AgencyApplication;
import com.voyra.crm.entity.KycDocument;
import com.voyra.crm.enums.ApplicationStatus;
import com.voyra.crm.enums.KycDocumentStatus;
import com.voyra.crm.enums.KycDocumentType;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.exception.NotFoundException;
import com.voyra.crm.repository.AgencyApplicationRepository;
import com.voyra.crm.repository.KycDocumentRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgencyApplicationServiceCoverageTest {

    private static final String TOKEN = "the-raw-upload-token-of-32-chars-or-more";

    @Mock private AgencyApplicationRepository applicationRepository;
    @Mock private KycDocumentRepository kycDocumentRepository;
    @Mock private FileStorageService fileStorageService;
    @Mock private LoginEmailService loginEmailService;
    @Mock private AgencyService agencyService;
    @Mock private NotificationService notificationService;
    @Mock private PlatformAuditService platformAuditService;
    @InjectMocks private AgencyApplicationService service;

    @BeforeEach
    void admin() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal("root-1", "root", UserType.SUPER_ADMIN, null), null, List.of()));
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    private static String sha256Hex(String s) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    }

    private void editableDraft() throws Exception {
        when(applicationRepository.findByIdForUpdate("app-1")).thenReturn(Optional.of(AgencyApplication.builder().id("app-1")
                .status(ApplicationStatus.DRAFT).uploadTokenHash(sha256Hex(TOKEN)).uploadTokenExpiresAt(Instant.now().plusSeconds(600)).build()));
    }

    @Test
    void aGivenBaseCurrencyAndCinAreKeptTrimmedAndUpperCased() {
        AgencyApplicationCreateRequest r = new AgencyApplicationCreateRequest();
        r.setAgencyName("Sky Trips");
        r.setPan("ABCDE1234F");
        r.setOwnerName("Owner A");
        r.setOwnerEmail(" owner-a@example.test ");
        r.setOwnerPhone("0000000000");
        r.setBaseCurrencyCode(" AED ");
        r.setCin(" u12345mh2020ptc123456 ");

        service.create(r);

        ArgumentCaptor<AgencyApplication> saved = ArgumentCaptor.forClass(AgencyApplication.class);
        verify(applicationRepository).save(saved.capture());
        assertThat(saved.getValue().getBaseCurrencyCode()).isEqualTo("AED");
        assertThat(saved.getValue().getCin()).isEqualTo("U12345MH2020PTC123456");
        assertThat(saved.getValue().getOwnerEmail()).isEqualTo("owner-a@example.test");
        assertThat(saved.getValue().getStatus()).isEqualTo(ApplicationStatus.DRAFT);

        r.setBaseCurrencyCode(" ");
        service.create(r);
        verify(applicationRepository, org.mockito.Mockito.times(2)).save(saved.capture());
        assertThat(saved.getValue().getBaseCurrencyCode()).isEqualTo("INR");
    }

    @Test
    void anUploadWithoutAContentTypeIsStoredWithoutOneAndALongTypeIsCut() throws Exception {
        editableDraft();
        when(fileStorageService.store(eq(AgencyApplicationService.FILE_SCOPE), eq(AgencyApplicationService.FILE_CATEGORY), eq("app-1"), any()))
                .thenReturn("platform/kyc/app-1/pan.pdf");
        byte[] pdf = "%PDF-1.4 pan".getBytes(StandardCharsets.UTF_8);

        service.attachDocument("app-1", TOKEN, KycDocumentType.PAN, new MockMultipartFile("file", "pan.pdf", null, pdf));
        service.attachDocument("app-1", TOKEN, KycDocumentType.PAN, new MockMultipartFile("file", "pan.pdf", "application/" + "x".repeat(120), pdf));

        ArgumentCaptor<KycDocument> docs = ArgumentCaptor.forClass(KycDocument.class);
        verify(kycDocumentRepository, org.mockito.Mockito.times(2)).save(docs.capture());
        assertThat(docs.getAllValues().get(0).getContentType()).isNull();
        assertThat(docs.getAllValues().get(1).getContentType()).hasSize(100).startsWith("application/xxx");
    }

    @Test
    void aKycFileNeedsANamedPdfOrImage() throws Exception {
        editableDraft();
        MultipartFile unnamed = mock(MultipartFile.class);
        when(unnamed.getOriginalFilename()).thenReturn(null);

        assertThatThrownBy(() -> service.attachDocument("app-1", TOKEN, KycDocumentType.PAN, unnamed))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Upload a PDF, JPG, PNG or WEBP file");
        assertThatThrownBy(() -> service.attachDocument("app-1", TOKEN, KycDocumentType.PAN,
                new MockMultipartFile("file", "pancard", "application/pdf", new byte[]{1})))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Upload a PDF, JPG, PNG or WEBP file");
        verifyNoInteractions(fileStorageService);
    }

    @Test
    void anUploadLinkWithoutATokenOrExpiryIsRefused() throws Exception {
        AgencyApplication app = AgencyApplication.builder().id("app-1").uploadTokenHash(sha256Hex(TOKEN)).uploadTokenExpiresAt(null).build();
        assertThatThrownBy(() -> service.assertUploadToken(app, TOKEN))
                .isInstanceOf(AccessDeniedException.class).hasMessage("This upload link is invalid or has expired");
        app.setUploadTokenExpiresAt(Instant.now().plusSeconds(60));
        assertThatThrownBy(() -> service.assertUploadToken(app, null))
                .isInstanceOf(AccessDeniedException.class).hasMessage("This upload link is invalid or has expired");
    }

    @Test
    void theListUsesTheStatusesAskedForOrTheReviewableOnes() {
        PageRequest page = PageRequest.of(0, 10);
        when(applicationRepository.findByStatusInOrderBySubmittedAtDesc(List.of(ApplicationStatus.APPROVED), page))
                .thenReturn(new PageImpl<>(List.of(AgencyApplication.builder().id("app-9").status(ApplicationStatus.APPROVED).build()), page, 1));
        EnumSet<ApplicationStatus> reviewable = EnumSet.of(ApplicationStatus.SUBMITTED, ApplicationStatus.UNDER_REVIEW, ApplicationStatus.MORE_INFO);
        when(applicationRepository.findByStatusInOrderBySubmittedAtDesc(reviewable, page)).thenReturn(new PageImpl<>(List.of(), page, 0));
        when(kycDocumentRepository.countByApplicationId("app-9")).thenReturn(3L);

        assertThat(service.list(List.of(ApplicationStatus.APPROVED), page).getContent())
                .singleElement().satisfies(a -> assertThat(a.getDocumentCount()).isEqualTo(3L));
        assertThat(service.list(null, page).getContent()).isEmpty();
        assertThat(service.list(List.of(), page).getContent()).isEmpty();
    }

    @Test
    void oneApplicationIsReadWithItsDocuments() {
        when(applicationRepository.findById("app-1")).thenReturn(Optional.of(AgencyApplication.builder().id("app-1")
                .status(ApplicationStatus.SUBMITTED).agencyName("Sky Trips").build()));
        when(kycDocumentRepository.findByApplicationIdOrderByCreatedAt("app-1")).thenReturn(List.of(KycDocument.builder().id("D1")
                .type(KycDocumentType.PAN).fileName("pan.pdf").sizeBytes(10L).status(KycDocumentStatus.PENDING).build()));

        AgencyApplicationResponse r = service.get("app-1");

        assertThat(r.getAgencyName()).isEqualTo("Sky Trips");
        assertThat(r.getDocumentCount()).isEqualTo(1L);
        assertThat(r.getDocuments()).extracting(KycDocumentResponse::getFileName).containsExactly("pan.pdf");
    }

    @Test
    void unknownApplicationsAndDocumentsAreNotFound() {
        KycDocumentDecisionRequest docDecision = new KycDocumentDecisionRequest();
        docDecision.setStatus(KycDocumentStatus.VERIFIED);
        ApplicationDecisionRequest decision = new ApplicationDecisionRequest();
        decision.setStatus(ApplicationStatus.UNDER_REVIEW);

        assertThatThrownBy(() -> service.get("nope")).isInstanceOf(NotFoundException.class).hasMessage("Application not found");
        assertThatThrownBy(() -> service.decideDocument("nope", "D1", docDecision)).isInstanceOf(NotFoundException.class).hasMessage("Application not found");
        assertThatThrownBy(() -> service.decide("nope", decision)).isInstanceOf(NotFoundException.class).hasMessage("Application not found");
        assertThatThrownBy(() -> service.submit("nope", TOKEN)).isInstanceOf(NotFoundException.class).hasMessage("Application not found");
        assertThatThrownBy(() -> service.openDocument("app-1", "D9")).isInstanceOf(NotFoundException.class).hasMessage("Document not found");
        verifyNoInteractions(platformAuditService, fileStorageService);
    }

    @Test
    void aDecisionWithoutANoteKeepsTheEarlierReviewNote() {
        AgencyApplication app = AgencyApplication.builder().id("app-1").status(ApplicationStatus.SUBMITTED).reviewNote("earlier").build();
        when(applicationRepository.findByIdForUpdate("app-1")).thenReturn(Optional.of(app));
        ApplicationDecisionRequest r = new ApplicationDecisionRequest();
        r.setStatus(ApplicationStatus.UNDER_REVIEW);

        AgencyApplicationResponse response = service.decide("app-1", r);

        assertThat(response.getStatus()).isEqualTo(ApplicationStatus.UNDER_REVIEW);
        assertThat(response.getReviewNote()).isEqualTo("earlier");
        assertThat(response.getResumeUrl()).isNull();
        assertThat(app.getReviewedBy()).isEqualTo("root-1");
        verifyNoInteractions(notificationService);
    }
}
