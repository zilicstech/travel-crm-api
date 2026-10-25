package com.voyra.crm.service;

import com.voyra.crm.dto.BankTopupCreateRequest;
import com.voyra.crm.dto.TopupApproveRequest;
import com.voyra.crm.dto.TopupRejectRequest;
import com.voyra.crm.dto.TopupResponse;
import com.voyra.crm.entity.TopupRequest;
import com.voyra.crm.entity.Wallet;
import com.voyra.crm.entity.WalletEntry;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.enums.PlatformAuditAction;
import com.voyra.crm.enums.TopupMode;
import com.voyra.crm.enums.TopupStatus;
import com.voyra.crm.enums.WalletEntryType;
import com.voyra.crm.enums.WalletStatus;
import com.voyra.crm.exception.FieldValidationException;
import com.voyra.crm.exception.NotFoundException;
import com.voyra.crm.repository.TenantRepository;
import com.voyra.crm.repository.TopupRequestRepository;
import com.voyra.crm.repository.WalletRepository;
import com.voyra.crm.util.UniqueIdResolver;
import com.voyra.crm.util.UploadValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Bank-transfer top-ups: the owner submits a UTR and proof; the platform desk approves (credit) or rejects. */
@Service
@RequiredArgsConstructor
public class TopupService {

    static final long MAX_PROOF_BYTES = 5L * 1024 * 1024;
    private static final Set<String> PROOF_EXTENSIONS = Set.of("pdf", "jpg", "jpeg", "png", "webp");
    private static final String PROOF_TYPE_MESSAGE = "Upload a PDF, JPG, PNG or WEBP file";
    private static final String REF_TYPE = "TOPUP_REQUEST";

    private final TopupRequestRepository topupRepository;
    private final WalletRepository walletRepository;
    private final TenantRepository tenantRepository;
    private final WalletService walletService;
    private final FileStorageService fileStorageService;
    private final PlatformAuditService platformAuditService;

    /** Upper-case with every whitespace character removed, so "sbin 123" and "SBIN123" are the same UTR. */
    public static String normalize(String bankRef) {
        return bankRef.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    @Transactional
    public TopupResponse createBank(String tenantId, String userId, String userName, BankTopupCreateRequest request) {
        Wallet wallet = walletRepository.findByTenantId(tenantId)
                .orElseThrow(() -> new NotFoundException("Wallet not found for this agency"));
        if (wallet.getStatus() != WalletStatus.OPEN) {
            throw new IllegalStateException("This wallet is closed");
        }
        String normalized = normalize(request.getBankRef());
        if (normalized.length() < 4) {
            throw new FieldValidationException("bankRef", "Bank reference must be at least 4 letters or digits");
        }
        if (topupRepository.existsByBankRefNormalized(normalized)) {
            throw new FieldValidationException("bankRef", "This bank reference has already been used");
        }
        MultipartFile proof = request.getProof();
        String name = proof.getOriginalFilename() == null ? "" : proof.getOriginalFilename();
        if (!PROOF_EXTENSIONS.contains(name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException(PROOF_TYPE_MESSAGE);
        }
        try {
            UploadValidator.assertAllowed(proof, MAX_PROOF_BYTES);
        } catch (IllegalArgumentException e) {
            throw proof.getSize() > MAX_PROOF_BYTES ? e : new IllegalArgumentException(PROOF_TYPE_MESSAGE);
        }
        String id = UniqueIdResolver.resolve(topupRepository::existsById);
        String key = fileStorageService.store("platform", "wallet-topups/" + tenantId, id, proof);
        TopupRequest saved = topupRepository.save(TopupRequest.builder()
                .id(id).walletId(wallet.getId()).tenantId(tenantId)
                .mode(TopupMode.BANK_TRANSFER).amount(request.getAmount()).status(TopupStatus.PENDING)
                .bankRef(request.getBankRef().trim()).bankRefNormalized(normalized)
                .proofFileKey(key)
                .proofFileName(name.length() <= 255 ? name : name.substring(name.length() - 255))
                .proofContentType(MediaTypeFactory.getMediaType(name).orElse(MediaType.APPLICATION_OCTET_STREAM).toString())
                .note(StringUtils.hasText(request.getNote()) ? request.getNote().trim() : null)
                .requestedBy(userId).requestedByName(userName)
                .createdAt(Instant.now()).build());
        return toResponse(saved, null);
    }

    @Transactional(readOnly = true)
    public List<TopupResponse> listForAgency(String tenantId) {
        return topupRepository.findByTenantIdOrderByCreatedAtDesc(tenantId).stream().map(t -> toResponse(t, null)).toList();
    }

    @Transactional(readOnly = true)
    public List<TopupResponse> listForPlatform(TopupStatus status) {
        List<TopupRequest> rows = status == null
                ? topupRepository.findAllByOrderByCreatedAtDesc() : topupRepository.findByStatusOrderByCreatedAtDesc(status);
        Map<String, String> names = tenantRepository.findAllById(rows.stream().map(TopupRequest::getTenantId).distinct().toList())
                .stream().collect(Collectors.toMap(Tenant::getId, Tenant::getAgencyName));
        return rows.stream().map(t -> toResponse(t, names.getOrDefault(t.getTenantId(), ""))).toList();
    }

    @Transactional
    public TopupResponse approve(String id, String reviewerId, TopupApproveRequest request) {
        TopupRequest t = lockPending(id);
        WalletEntry entry = walletService.credit(t.getTenantId(), WalletEntryType.TOPUP, request.getApprovedAmount(),
                "topup:" + t.getId(), REF_TYPE, t.getId(), "Bank transfer top-up, UTR " + t.getBankRef());
        t.setStatus(TopupStatus.APPROVED);
        t.setApprovedAmount(request.getApprovedAmount());
        t.setCreditEntryId(entry.getId());
        decided(t, reviewerId, request.getNote());
        platformAuditService.record(PlatformAuditAction.TOPUP_DECISION, REF_TYPE, id,
                Map.of("decision", "APPROVED", "tenantId", t.getTenantId(), "requested", t.getAmount().toPlainString(),
                        "approved", request.getApprovedAmount().toPlainString()));
        return toResponse(t, null);
    }

    @Transactional
    public TopupResponse reject(String id, String reviewerId, TopupRejectRequest request) {
        TopupRequest t = lockPending(id);
        t.setStatus(TopupStatus.REJECTED);
        decided(t, reviewerId, request.getNote());
        platformAuditService.record(PlatformAuditAction.TOPUP_DECISION, REF_TYPE, id,
                Map.of("decision", "REJECTED", "tenantId", t.getTenantId(), "requested", t.getAmount().toPlainString()));
        return toResponse(t, null);
    }

    @Transactional
    public ProofContent openProof(String id) {
        TopupRequest t = topupRepository.findById(id).orElseThrow(() -> new NotFoundException("Top-up not found"));
        if (t.getProofFileKey() == null) {
            throw new NotFoundException("This top-up has no proof file");
        }
        Resource resource = fileStorageService.retrieve(t.getProofFileKey());
        platformAuditService.record(PlatformAuditAction.TOPUP_PROOF_VIEW, REF_TYPE, id, Map.of("tenantId", t.getTenantId()));
        // By extension, never the uploader-supplied type, so a proof cannot be served as HTML.
        return new ProofContent(resource, t.getProofFileName(),
                MediaTypeFactory.getMediaType(t.getProofFileName()).orElse(MediaType.APPLICATION_OCTET_STREAM).toString());
    }

    /** Not a DTO - unpacked into a binary response by the controller. */
    public record ProofContent(Resource resource, String fileName, String contentType) {
    }

    private TopupRequest lockPending(String id) {
        TopupRequest t = topupRepository.findByIdForUpdate(id).orElseThrow(() -> new NotFoundException("Top-up not found"));
        if (t.getStatus() != TopupStatus.PENDING) {
            throw new IllegalStateException("This top-up was already " + t.getStatus().name().toLowerCase(Locale.ROOT));
        }
        if (t.getMode() != TopupMode.BANK_TRANSFER) {
            throw new IllegalStateException("Only bank-transfer top-ups are decided by hand");
        }
        return t;
    }

    private static void decided(TopupRequest t, String reviewerId, String note) {
        t.setReviewedBy(reviewerId);
        t.setReviewedAt(Instant.now());
        t.setReviewNote(StringUtils.hasText(note) ? note.trim() : null);
    }

    private static TopupResponse toResponse(TopupRequest t, String agencyName) {
        return TopupResponse.builder().id(t.getId()).mode(t.getMode()).amount(t.getAmount())
                .approvedAmount(t.getApprovedAmount()).status(t.getStatus()).bankRef(t.getBankRef())
                .gatewayOrderId(t.getGatewayOrderId()).note(t.getNote()).reviewNote(t.getReviewNote())
                .requestedByName(t.getRequestedByName()).createdAt(t.getCreatedAt()).reviewedAt(t.getReviewedAt())
                .tenantId(agencyName == null ? null : t.getTenantId()).agencyName(agencyName).build();
    }
}
