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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * A PNR or supplier reference on a lead, optionally tied to one service instance. File upload
 * is deliberately out of scope for this pass - {@code fileKey} stays null until that lands.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LeadVoucherService {

    private final LeadVoucherRepository leadVoucherRepository;
    private final LeadRepository leadRepository;
    private final LeadServiceRepository leadServiceRepository;
    private final AgentRepository agentRepository;
    private final LeadTimelineService leadTimelineService;

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

    @Transactional
    public void deleteVoucher(String leadId, String voucherId) {
        findAccessibleLead(leadId);
        LeadVoucher voucher = leadVoucherRepository.findByIdAndLeadId(voucherId, leadId)
                .orElseThrow(() -> new IllegalArgumentException("Voucher not found: " + voucherId));
        leadVoucherRepository.delete(voucher);

        leadTimelineService.record(leadId, voucher.getServiceId(), LeadTimelineEventType.VOUCHER_REMOVED,
                voucher.getSupplier() + " " + voucher.getReferenceNumber() + " removed");
        log.info("Voucher removed: leadId={}, voucherId={}", leadId, voucherId);
    }

    @Transactional(readOnly = true)
    public List<VoucherResponse> listForLead(String leadId) {
        return leadVoucherRepository.findByLeadIdOrderByCreatedAtDesc(leadId).stream()
                .map(this::toResponse).toList();
    }

    private Lead findAccessibleLead(String leadId) {
        Lead lead = leadRepository.findById(leadId)
                .orElseThrow(() -> new IllegalArgumentException("Lead not found: " + leadId));
        CustomUserPrincipal principal = SecurityContextUtil.getCurrentUserOrThrow();
        if (principal.isAgent() && !lead.getAssignedTo().equals(principal.userId())
                && !LeadAccessChecker.hasServiceAccess(leadServiceRepository, agentRepository, leadId, principal.userId())) {
            throw new AccessDeniedException("This lead is not assigned to you");
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
                .build();
    }
}
