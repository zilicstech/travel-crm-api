package com.voyra.crm.service;

import com.voyra.crm.dto.AgencyApplicationCreateRequest;
import com.voyra.crm.dto.AgencyApplicationCreatedResponse;
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
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Public agency applications: an agency applies without logging in, then uploads KYC files and submits
 * using the one-time upload token returned at creation. The platform desk reviews them (Task 5).
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
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AgencyApplicationRepository applicationRepository;
    private final KycDocumentRepository kycDocumentRepository;
    private final FileStorageService fileStorageService;
    private final LoginEmailService loginEmailService;

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
