package com.voyra.crm.service;

import com.voyra.crm.dto.AgencyApplicationCreateRequest;
import com.voyra.crm.dto.AgencyApplicationCreatedResponse;
import com.voyra.crm.dto.AgencyApplicationResponse;
import com.voyra.crm.dto.AgencyResponse;
import com.voyra.crm.dto.ApplicationDecisionRequest;
import com.voyra.crm.dto.KycDocumentDecisionRequest;
import com.voyra.crm.dto.PagedResponse;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.enums.PlatformAuditAction;
import com.voyra.crm.security.SecurityContextUtil;
import com.voyra.crm.dto.KycDocumentResponse;
import com.voyra.crm.entity.AgencyApplication;
import com.voyra.crm.entity.KycDocument;
import com.voyra.crm.enums.ApplicationStatus;
import com.voyra.crm.enums.KycDocumentStatus;
import com.voyra.crm.enums.KycDocumentType;
import com.voyra.crm.exception.NotFoundException;
import com.voyra.crm.repository.AgencyApplicationRepository;
import com.voyra.crm.repository.KycDocumentRepository;
import com.voyra.crm.util.UniqueIdResolver;
import com.voyra.crm.util.UploadValidator;
import com.voyra.crm.util.ValidationPatterns;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Public agency applications: an agency applies without logging in, then uploads KYC files and submits
 * using the one-time upload token returned at creation. The platform desk (SUPER_ADMIN) then reviews them and
 * either sends them back, rejects them, or approves them into a provisioned agency.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgencyApplicationService {

    public static final String FILE_SCOPE = "platform";
    public static final String FILE_CATEGORY = "kyc";
    static final long MAX_KYC_BYTES = 5L * 1024 * 1024;
    static final Duration UPLOAD_TOKEN_TTL = Duration.ofHours(24);
    static final int MAX_DOCUMENTS = 20;
    private static final Set<String> KYC_EXTENSIONS = Set.of("pdf", "jpg", "jpeg", "png", "webp");

    private static final Set<ApplicationStatus> OPEN = EnumSet.of(ApplicationStatus.DRAFT,
            ApplicationStatus.SUBMITTED, ApplicationStatus.UNDER_REVIEW, ApplicationStatus.MORE_INFO);
    /** The applicant may still change documents and submit only while the application is with them. */
    private static final Set<ApplicationStatus> EDITABLE = EnumSet.of(ApplicationStatus.DRAFT, ApplicationStatus.MORE_INFO);
    /** With the platform desk: it may decide or approve. */
    private static final Set<ApplicationStatus> REVIEWABLE = EnumSet.of(ApplicationStatus.SUBMITTED,
            ApplicationStatus.UNDER_REVIEW, ApplicationStatus.MORE_INFO);
    private static final Set<ApplicationStatus> DECISIONS = EnumSet.of(ApplicationStatus.UNDER_REVIEW,
            ApplicationStatus.MORE_INFO, ApplicationStatus.REJECTED);
    static final Duration MORE_INFO_TOKEN_TTL = Duration.ofDays(7);
    private static final String ENTITY_APPLICATION = "AGENCY_APPLICATION";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AgencyApplicationRepository applicationRepository;
    private final KycDocumentRepository kycDocumentRepository;
    private final FileStorageService fileStorageService;
    private final LoginEmailService loginEmailService;
    private final AgencyService agencyService;
    private final PlatformAuditService platformAuditService;

    @Transactional
    public AgencyApplicationCreatedResponse create(AgencyApplicationCreateRequest request) {
        if (StringUtils.hasText(request.getWebsite())) {
            // A bot gets a believable answer; the id matches nothing, so its uploads 404.
            log.info("Agency application dropped by honeypot");
            return AgencyApplicationCreatedResponse.builder().id(UUID.randomUUID().toString()).uploadToken(newToken()).build();
        }
        String email = request.getOwnerEmail().trim();
        loginEmailService.assertFree(email);
        Instant now = Instant.now();
        AgencyApplication open = applicationRepository.findFirstByOwnerEmailIgnoreCaseAndStatusIn(email, OPEN).orElse(null);
        if (open != null && open.getStatus() != ApplicationStatus.DRAFT) {
            throw new IllegalArgumentException("An application for this email is already under review");
        }
        if (open != null && open.getUploadTokenExpiresAt().isAfter(now)) {
            throw new IllegalStateException("An application for this email was started in the last 24 hours. "
                    + "Continue it in the same browser or try again after 24 hours.");
        }
        String gstin = upperOrNull(request.getGstin());
        String stateCode = trimToNull(request.getStateCode());
        ValidationPatterns.requireGstinMatchesState(gstin, stateCode);

        // An abandoned draft (token expired) or a rejection is reused, so the platform desk sees one history per email.
        AgencyApplication app = open != null ? open : applicationRepository
                .findFirstByOwnerEmailIgnoreCaseAndStatusIn(email, EnumSet.of(ApplicationStatus.REJECTED))
                .orElseGet(() -> AgencyApplication.builder()
                        .id(UniqueIdResolver.resolve(applicationRepository::existsById)).build());
        String token = newToken();
        app.setStatus(ApplicationStatus.DRAFT);
        app.setAgencyName(request.getAgencyName().trim());
        app.setLegalName(trimToNull(request.getLegalName()));
        app.setTradeName(trimToNull(request.getTradeName()));
        app.setPan(request.getPan().trim());
        app.setGstin(gstin);
        app.setCin(upperOrNull(request.getCin()));
        app.setAddress(trimToNull(request.getAddress()));
        app.setCity(trimToNull(request.getCity()));
        app.setStateCode(stateCode);
        app.setPincode(trimToNull(request.getPincode()));
        app.setOwnerName(request.getOwnerName().trim());
        app.setOwnerEmail(email);
        app.setOwnerPhone(request.getOwnerPhone().trim());
        app.setExpectedMonthlyVolume(trimToNull(request.getExpectedMonthlyVolume()));
        app.setBaseCurrencyCode(StringUtils.hasText(request.getBaseCurrencyCode()) ? request.getBaseCurrencyCode().trim() : "INR");
        app.setConsentAt(now);
        app.setUploadTokenHash(sha256Hex(token));
        app.setUploadTokenExpiresAt(now.plus(UPLOAD_TOKEN_TTL));
        app.setSubmittedAt(null);
        app.setReviewedBy(null);
        app.setReviewedAt(null);
        app.setReviewNote(null);
        app.setRejectionReason(null);
        applicationRepository.save(app);
        return AgencyApplicationCreatedResponse.builder().id(app.getId()).uploadToken(token).build();
    }

    @Transactional
    public KycDocumentResponse attachDocument(String applicationId, String uploadToken, KycDocumentType type, MultipartFile file) {
        AgencyApplication app = editable(applicationId, uploadToken);
        if (kycDocumentRepository.countByApplicationId(app.getId()) >= MAX_DOCUMENTS) {
            throw new IllegalStateException("This application already has the maximum of " + MAX_DOCUMENTS + " documents");
        }
        if (!KYC_EXTENSIONS.contains(extensionOf(file.getOriginalFilename()))) {
            throw new IllegalArgumentException("Upload a PDF, JPG, PNG or WEBP file");
        }
        UploadValidator.assertAllowed(file, MAX_KYC_BYTES);
        String fileKey = fileStorageService.store(FILE_SCOPE, FILE_CATEGORY, app.getId(), file);
        KycDocument doc = KycDocument.builder()
                .id(UniqueIdResolver.resolve(kycDocumentRepository::existsById))
                .applicationId(app.getId())
                .type(type)
                .fileKey(fileKey)
                .fileName(fitFileName(file.getOriginalFilename()))
                .contentType(file.getContentType() == null || file.getContentType().length() <= 100
                        ? file.getContentType() : file.getContentType().substring(0, 100))
                .sizeBytes(file.getSize())
                .status(KycDocumentStatus.PENDING)
                .build();
        kycDocumentRepository.save(doc);
        return toResponse(doc);
    }

    @Transactional
    public void submit(String applicationId, String uploadToken) {
        AgencyApplication app = editable(applicationId, uploadToken);
        if (kycDocumentRepository.countByApplicationIdAndTypeAndStatusNot(app.getId(), KycDocumentType.PAN,
                KycDocumentStatus.REJECTED) == 0) {
            throw new IllegalStateException("Upload the PAN card first");
        }
        app.setStatus(ApplicationStatus.SUBMITTED);
        app.setSubmittedAt(Instant.now());
    }

    /** @throws AccessDeniedException when the token is missing, wrong or past its 24-hour life */
    public void assertUploadToken(AgencyApplication app, String uploadToken) {
        boolean valid = uploadToken != null
                && app.getUploadTokenExpiresAt() != null && app.getUploadTokenExpiresAt().isAfter(Instant.now())
                && MessageDigest.isEqual(sha256Hex(uploadToken).getBytes(StandardCharsets.US_ASCII),
                        app.getUploadTokenHash().getBytes(StandardCharsets.US_ASCII));
        if (!valid) {
            throw new AccessDeniedException("This upload link is invalid or has expired");
        }
    }

    @Transactional(readOnly = true)
    public PagedResponse<AgencyApplicationResponse> list(Collection<ApplicationStatus> statuses, Pageable pageable) {
        Collection<ApplicationStatus> filter = statuses == null || statuses.isEmpty() ? REVIEWABLE : statuses;
        return PagedResponse.from(applicationRepository.findByStatusInOrderBySubmittedAtDesc(filter, pageable),
                app -> toResponse(app, null));
    }

    @Transactional(readOnly = true)
    public AgencyApplicationResponse get(String id) {
        return withDocuments(find(id));
    }

    /** Not transactional: reading object storage stays outside a transaction (blueprint §8.6). Every view is audited. */
    public KycFileContent openDocument(String applicationId, String documentId) {
        KycDocument doc = findDocument(applicationId, documentId);
        Resource resource = fileStorageService.retrieve(doc.getFileKey());
        platformAuditService.record(PlatformAuditAction.KYC_DOCUMENT_VIEW, "KYC_DOCUMENT", doc.getId(),
                Map.of("applicationId", applicationId, "type", doc.getType().name()));
        // By extension, never the applicant-supplied content type, so an upload cannot be served as HTML.
        String contentType = MediaTypeFactory.getMediaType(doc.getFileName()).orElse(MediaType.APPLICATION_OCTET_STREAM).toString();
        return new KycFileContent(resource, doc.getFileName(), contentType);
    }

    /** Not a DTO - never serialized to JSON, only unpacked into a binary response by the controller. */
    public record KycFileContent(Resource resource, String fileName, String contentType) {
    }

    @Transactional
    public KycDocumentResponse decideDocument(String applicationId, String documentId, KycDocumentDecisionRequest request) {
        KycDocument doc = findDocument(applicationId, documentId);
        doc.setStatus(request.getStatus());
        doc.setNote(trimToNull(request.getNote()));
        doc.setReviewedBy(SecurityContextUtil.getCurrentUserOrThrow().userId());
        doc.setReviewedAt(Instant.now());
        return toResponse(doc);
    }

    /** UNDER_REVIEW, MORE_INFO (re-opens the form with a fresh 7-day link) or REJECTED (needs a reason). */
    @Transactional
    public AgencyApplicationResponse decide(String id, ApplicationDecisionRequest request) {
        ApplicationStatus status = request.getStatus();
        if (!DECISIONS.contains(status)) {
            throw new IllegalArgumentException("A decision is UNDER_REVIEW, MORE_INFO or REJECTED; approve has its own action");
        }
        String reason = trimToNull(request.getRejectionReason());
        if (status == ApplicationStatus.REJECTED && reason == null) {
            throw new IllegalArgumentException("Give a reason for the rejection");
        }
        AgencyApplication app = reviewable(id);
        ApplicationStatus previous = app.getStatus();
        app.setStatus(status);
        app.setRejectionReason(status == ApplicationStatus.REJECTED ? reason : null);
        if (request.getNote() != null) {
            app.setReviewNote(trimToNull(request.getNote()));
        }
        markReviewed(app);
        String token = null;
        if (status == ApplicationStatus.MORE_INFO) {
            token = newToken();
            app.setUploadTokenHash(sha256Hex(token));
            app.setUploadTokenExpiresAt(Instant.now().plus(MORE_INFO_TOKEN_TTL));
        }
        platformAuditService.record(PlatformAuditAction.APPLICATION_DECISION, ENTITY_APPLICATION, app.getId(),
                Map.of("from", previous.name(), "to", status.name()));
        AgencyApplicationResponse response = withDocuments(app);
        if (token != null) {
            response.setResumeUrl("/apply?application=" + app.getId() + "&token=" + token);
        }
        return response;
    }

    /** Provisions the agency (tenant, schema, owner login) and closes the application. Once only. */
    @Transactional
    public AgencyResponse approve(String id, String ownerPassword) {
        AgencyApplication app = reviewable(id);
        Tenant tenant = agencyService.provisionFromApplication(app, ownerPassword);
        app.setTenantId(tenant.getId());
        app.setStatus(ApplicationStatus.APPROVED);
        markReviewed(app);
        platformAuditService.record(PlatformAuditAction.APPLICATION_APPROVE, ENTITY_APPLICATION, app.getId(),
                Map.of("tenantId", tenant.getId()));
        return agencyService.toResponse(tenant, false);
    }

    private AgencyApplication find(String id) {
        return applicationRepository.findById(id).orElseThrow(() -> new NotFoundException("Application not found"));
    }

    private AgencyApplication reviewable(String id) {
        AgencyApplication app = find(id);
        if (!REVIEWABLE.contains(app.getStatus())) {
            throw new IllegalStateException("This application is " + app.getStatus() + " and can no longer be decided");
        }
        return app;
    }

    private KycDocument findDocument(String applicationId, String documentId) {
        return kycDocumentRepository.findByIdAndApplicationId(documentId, applicationId)
                .orElseThrow(() -> new NotFoundException("Document not found"));
    }

    private static void markReviewed(AgencyApplication app) {
        app.setReviewedBy(SecurityContextUtil.getCurrentUserOrThrow().userId());
        app.setReviewedAt(Instant.now());
    }

    private AgencyApplicationResponse withDocuments(AgencyApplication app) {
        return toResponse(app, kycDocumentRepository.findByApplicationIdOrderByCreatedAt(app.getId()).stream()
                .map(AgencyApplicationService::toResponse).toList());
    }

    private static AgencyApplicationResponse toResponse(AgencyApplication a, List<KycDocumentResponse> documents) {
        return AgencyApplicationResponse.builder()
                .id(a.getId())
                .status(a.getStatus())
                .agencyName(a.getAgencyName())
                .legalName(a.getLegalName())
                .tradeName(a.getTradeName())
                .pan(a.getPan())
                .gstin(a.getGstin())
                .cin(a.getCin())
                .address(a.getAddress())
                .city(a.getCity())
                .stateCode(a.getStateCode())
                .pincode(a.getPincode())
                .ownerName(a.getOwnerName())
                .ownerEmail(a.getOwnerEmail())
                .ownerPhone(a.getOwnerPhone())
                .expectedMonthlyVolume(a.getExpectedMonthlyVolume())
                .baseCurrencyCode(a.getBaseCurrencyCode())
                .consentAt(a.getConsentAt())
                .submittedAt(a.getSubmittedAt())
                .reviewedBy(a.getReviewedBy())
                .reviewedAt(a.getReviewedAt())
                .reviewNote(a.getReviewNote())
                .rejectionReason(a.getRejectionReason())
                .tenantId(a.getTenantId())
                .createdAt(a.getCreatedAt())
                .updatedAt(a.getUpdatedAt())
                .documents(documents)
                .build();
    }

    private AgencyApplication editable(String applicationId, String uploadToken) {
        AgencyApplication app = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new NotFoundException("Application not found"));
        assertUploadToken(app, uploadToken);
        if (!EDITABLE.contains(app.getStatus())) {
            throw new IllegalStateException("This application has already been submitted");
        }
        return app;
    }

    private static KycDocumentResponse toResponse(KycDocument d) {
        return KycDocumentResponse.builder()
                .id(d.getId())
                .type(d.getType())
                .fileName(d.getFileName())
                .sizeBytes(d.getSizeBytes())
                .status(d.getStatus())
                .note(d.getNote())
                .build();
    }

    private static String extensionOf(String name) {
        int dot = name != null ? name.lastIndexOf('.') : -1;
        return dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    /** file_name is VARCHAR(255): shorten the base name, keep the extension. */
    static String fitFileName(String name) {
        if (name.length() <= 255) {
            return name;
        }
        String ext = name.substring(name.lastIndexOf('.')); // present: the extension was checked first
        return name.substring(0, 255 - ext.length()) + ext;
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static String upperOrNull(String s) {
        String t = trimToNull(s);
        return t == null ? null : t.toUpperCase(Locale.ROOT);
    }

    private static String trimToNull(String s) {
        return StringUtils.hasText(s) ? s.trim() : null;
    }
}
