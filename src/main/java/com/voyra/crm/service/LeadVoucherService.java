package com.voyra.crm.service;

import com.voyra.crm.dto.VoucherCreateRequest;
import com.voyra.crm.dto.VoucherResponse;
import com.voyra.crm.entity.Lead;
import com.voyra.crm.entity.LeadVoucher;
import com.voyra.crm.enums.LeadTimelineEventType;
import com.voyra.crm.repository.AgentRepository;
import com.voyra.crm.repository.LeadRepository;
import com.voyra.crm.repository.LeadServiceRepository;
import com.voyra.crm.repository.LeadVoucherRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import com.voyra.crm.security.SecurityContextUtil;
import com.voyra.crm.util.LeadAccessChecker;
import com.voyra.crm.util.UniqueIdResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;

/**
 * A PNR or supplier reference on a lead, optionally tied to one service instance. A voucher
 * can be saved without a file and have one attached later via {@link #attachFile} - see
 * {@link #toResponse} for how {@code hasFile} reflects that. {@code fileKey} is opaque, from
 * {@link FileStorageService}, and deliberately never leaves this service in a response.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LeadVoucherService {

    private static final String VOUCHER_FILE_CATEGORY = "lead-vouchers";

    private final LeadVoucherRepository leadVoucherRepository;
    private final LeadRepository leadRepository;
    private final LeadServiceRepository leadServiceRepository;
    private final AgentRepository agentRepository;
    private final LeadTimelineService leadTimelineService;
    private final FileStorageService fileStorageService;

    @Transactional
    public VoucherResponse addVoucher(String leadId, VoucherCreateRequest request) {
        findAccessibleLead(leadId);
        String serviceLabel = null;
        if (request.getServiceId() != null) {
            serviceLabel = leadServiceRepository.findById(request.getServiceId())
                    .orElseThrow(() -> new IllegalArgumentException("Service not found: " + request.getServiceId()))
                    .getLabel();
        }

        LeadVoucher voucher = LeadVoucher.builder()
                .id(UniqueIdResolver.resolve(leadVoucherRepository::existsById))
                .leadId(leadId)
                .serviceId(request.getServiceId())
                .serviceLabel(serviceLabel)
                .supplier(request.getSupplier())
                .referenceNumber(request.getReferenceNumber())
                .voucherDate(request.getVoucherDate())
                .notes(request.getNotes())
                .createdAt(LocalDateTime.now())
                .createdBy(currentUserId())
                .build();
        leadVoucherRepository.save(voucher);

        leadTimelineService.record(leadId, request.getServiceId(), LeadTimelineEventType.VOUCHER_ADDED,
                request.getSupplier() + " " + request.getReferenceNumber() + " added");
        log.info("Voucher added: leadId={}, voucherId={}", leadId, voucher.getId());
        return toResponse(voucher);
    }

    /**
     * Not {@code @Transactional} - deleting the stored file is object storage work, which
     * blueprint §8.6 forbids inside a transaction. The repository delete below is
     * self-transactional (Spring Data wraps each call), so no explicit boundary is needed.
     */
    public void deleteVoucher(String leadId, String voucherId) {
        findAccessibleLead(leadId);
        LeadVoucher voucher = leadVoucherRepository.findByIdAndLeadId(voucherId, leadId)
                .orElseThrow(() -> new IllegalArgumentException("Voucher not found: " + voucherId));
        leadVoucherRepository.delete(voucher);
        if (voucher.getFileKey() != null) {
            fileStorageService.delete(voucher.getFileKey());
        }

        leadTimelineService.record(leadId, voucher.getServiceId(), LeadTimelineEventType.VOUCHER_REMOVED,
                voucher.getSupplier() + " " + voucher.getReferenceNumber() + " removed");
        log.info("Voucher removed: leadId={}, voucherId={}", leadId, voucherId);
    }

    @Transactional(readOnly = true)
    public List<VoucherResponse> listForLead(String leadId) {
        return leadVoucherRepository.findByLeadIdOrderByCreatedAtDesc(leadId).stream()
                .map(this::toResponse).toList();
    }

    /**
     * Attaches (or replaces) the confirmation file on an already-saved voucher. Not
     * {@code @Transactional} for the same §8.6 reason as {@link #deleteVoucher} - the store
     * call sits between two independently-transactional repository calls rather than inside
     * one application-level transaction.
     */
    public VoucherResponse attachFile(String leadId, String voucherId, MultipartFile file) {
        findAccessibleLead(leadId);
        LeadVoucher voucher = leadVoucherRepository.findByIdAndLeadId(voucherId, leadId)
                .orElseThrow(() -> new IllegalArgumentException("Voucher not found: " + voucherId));

        String tenantId = SecurityContextUtil.getCurrentUserOrThrow().tenantId();
        String previousFileKey = voucher.getFileKey();
        String fileKey = fileStorageService.store(tenantId, VOUCHER_FILE_CATEGORY, voucherId, file);

        voucher.setFileKey(fileKey);
        voucher.setFileName(file.getOriginalFilename());
        voucher.setContentType(file.getContentType());
        leadVoucherRepository.save(voucher);

        if (previousFileKey != null) {
            fileStorageService.delete(previousFileKey);
        }
        log.info("Voucher file attached: leadId={}, voucherId={}", leadId, voucherId);
        return toResponse(voucher);
    }

    /** Not {@code @Transactional} - retrieving the file is object storage work (§8.6). */
    public VoucherFileContent downloadFile(String leadId, String voucherId) {
        findAccessibleLead(leadId);
        LeadVoucher voucher = leadVoucherRepository.findByIdAndLeadId(voucherId, leadId)
                .orElseThrow(() -> new IllegalArgumentException("Voucher not found: " + voucherId));
        if (voucher.getFileKey() == null) {
            throw new IllegalArgumentException("No file attached to this voucher");
        }
        Resource resource = fileStorageService.retrieve(voucher.getFileKey());
        return new VoucherFileContent(resource, voucher.getFileName(), voucher.getContentType());
    }

    /** Not a DTO - never serialized to JSON, only unpacked into a binary response by the controller. */
    public record VoucherFileContent(Resource resource, String filename, String contentType) {
    }

    private Lead findAccessibleLead(String leadId) {
        Lead lead = leadRepository.findById(leadId)
                .orElseThrow(() -> new IllegalArgumentException("Lead not found: " + leadId));
        CustomUserPrincipal principal = SecurityContextUtil.getCurrentUserOrThrow();
        if (principal.isAgent() && !lead.getCreatedBy().equals(principal.userId())
                && !LeadAccessChecker.hasServiceAccess(leadServiceRepository, agentRepository, leadId, principal.userId())) {
            throw new AccessDeniedException("This lead is not accessible to you");
        }
        return lead;
    }

    private String currentUserId() {
        return SecurityContextUtil.getCurrentUserOrThrow().userId();
    }

    private VoucherResponse toResponse(LeadVoucher v) {
        return VoucherResponse.builder()
                .id(v.getId()).leadId(v.getLeadId()).serviceId(v.getServiceId()).serviceLabel(v.getServiceLabel())
                .supplier(v.getSupplier()).referenceNumber(v.getReferenceNumber()).voucherDate(v.getVoucherDate())
                .notes(v.getNotes()).createdAt(v.getCreatedAt())
                .hasFile(v.getFileKey() != null).fileName(v.getFileName())
                .build();
    }
}
