package com.voyra.crm.service;

import com.voyra.crm.dto.PagedResponse;
import com.voyra.crm.dto.VisaChecklistUpdateRequest;
import com.voyra.crm.dto.VisaCreateRequest;
import com.voyra.crm.dto.VisaDashboardSummaryResponse;
import com.voyra.crm.dto.VisaResponse;
import com.voyra.crm.entity.Agent;
import com.voyra.crm.entity.Customer;
import com.voyra.crm.entity.Visa;
import com.voyra.crm.enums.VisaStatus;
import com.voyra.crm.repository.AgentRepository;
import com.voyra.crm.repository.CustomerRepository;
import com.voyra.crm.repository.VisaRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import com.voyra.crm.security.SecurityContextUtil;
import com.voyra.crm.util.UniqueIdResolver;
import com.voyra.crm.util.VisaStatusCalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class VisaService {

    private final VisaRepository visaRepository;
    private final CustomerRepository customerRepository;
    private final AgentRepository agentRepository;

    @Transactional
    public VisaResponse createVisa(VisaCreateRequest request) {
        AuthorResolver.AuthorInfo owner = resolveOwningAgent(request.getAgentId());
        Customer customer = customerRepository.findById(request.getCustomerId())
                .orElseThrow(() -> new IllegalArgumentException("Customer not found: " + request.getCustomerId()));

        Visa visa = Visa.builder()
                .id(UniqueIdResolver.resolve(visaRepository::existsById))
                .customerId(customer.getId())
                .customerName(customer.getName())
                .agentId(owner.id())
                .agentName(owner.name())
                .leadId(request.getLeadId())
                .country(request.getCountry())
                .visaType(request.getVisaType())
                .passportNumber(request.getPassportNumber())
                .applicationDate(request.getApplicationDate() != null ? request.getApplicationDate() : java.time.LocalDate.now())
                .status(VisaStatus.DOCUMENTS_PENDING)
                .build();
        visaRepository.save(visa);

        log.info("Visa case created: visaId={}, customerId={}", visa.getId(), customer.getId());
        return toResponse(visa);
    }

    @Transactional(readOnly = true)
    public List<VisaResponse> listVisas() {
        CustomUserPrincipal principal = SecurityContextUtil.getCurrentUserOrThrow();
        List<Visa> base = principal.isAgent()
                ? visaRepository.findByAgentId(principal.userId())
                : visaRepository.findAll();
        return base.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public PagedResponse<VisaResponse> listVisas(Pageable pageable) {
        CustomUserPrincipal principal = SecurityContextUtil.getCurrentUserOrThrow();
        Page<Visa> page = principal.isAgent()
                ? visaRepository.findByAgentId(principal.userId(), pageable)
                : visaRepository.findAll(pageable);
        return PagedResponse.from(page, this::toResponse);
    }

    @Transactional(readOnly = true)
    public VisaResponse getVisa(String id) {
        return toResponse(findAccessibleVisa(id));
    }

    @Transactional
    public VisaResponse updateChecklist(String id, VisaChecklistUpdateRequest request) {
        Visa visa = findAccessibleVisa(id);

        if (request.getPassportCollected() != null) visa.setPassportCollected(request.getPassportCollected());
        if (request.getPhotosCollected() != null) visa.setPhotosCollected(request.getPhotosCollected());
        if (request.getFormsFilled() != null) visa.setFormsFilled(request.getFormsFilled());
        if (request.getAppointmentDate() != null) visa.setAppointmentDate(request.getAppointmentDate());
        if (request.getBiometricsDone() != null) visa.setBiometricsDone(request.getBiometricsDone());
        if (request.getSubmittedToEmbassy() != null) visa.setSubmittedToEmbassy(request.getSubmittedToEmbassy());
        if (request.getPassportReturned() != null) visa.setPassportReturned(request.getPassportReturned());
        if (request.getVisaValidity() != null) visa.setVisaValidity(request.getVisaValidity());
        if (request.getExpiryDate() != null) visa.setExpiryDate(request.getExpiryDate());

        // Approved/Rejected are mutually exclusive outcomes.
        if (Boolean.TRUE.equals(request.getApproved())) {
            visa.setApproved(true);
            visa.setRejected(false);
        } else if (Boolean.TRUE.equals(request.getRejected())) {
            visa.setRejected(true);
            visa.setApproved(false);
        }

        visa.setStatus(VisaStatusCalculator.calculate(visa));
        visaRepository.save(visa);
        log.info("Visa checklist updated: visaId={}, status={}", id, visa.getStatus());
        return toResponse(visa);
    }

    @Transactional(readOnly = true)
    public VisaDashboardSummaryResponse getDashboardSummary() {
        CustomUserPrincipal principal = SecurityContextUtil.getCurrentUserOrThrow();
        List<Visa> visas = principal.isAgent()
                ? visaRepository.findByAgentId(principal.userId())
                : visaRepository.findAll();

        return VisaDashboardSummaryResponse.builder()
                .total(visas.size())
                .documentsPending(visas.stream().filter(v -> v.getStatus() == VisaStatus.DOCUMENTS_PENDING).count())
                .appointmentScheduled(visas.stream().filter(v -> v.getStatus() == VisaStatus.APPOINTMENT_SCHEDULED).count())
                .submitted(visas.stream().filter(v -> v.getStatus() == VisaStatus.SUBMITTED).count())
                .approved(visas.stream().filter(v -> v.getStatus() == VisaStatus.APPROVED).count())
                .rejected(visas.stream().filter(v -> v.getStatus() == VisaStatus.REJECTED).count())
                .passportReturned(visas.stream().filter(Visa::getPassportReturned).count())
                .passportCollectionPending(visas.stream()
                        .filter(v -> v.getStatus() == VisaStatus.APPROVED && !Boolean.TRUE.equals(v.getPassportReturned()))
                        .count())
                .build();
    }

    private AuthorResolver.AuthorInfo resolveOwningAgent(String requestedAgentId) {
        CustomUserPrincipal principal = SecurityContextUtil.getCurrentUserOrThrow();
        if (principal.isAgent()) {
            Agent agent = agentRepository.findById(principal.userId())
                    .orElseThrow(() -> new IllegalStateException("Agent not found: " + principal.userId()));
            return new AuthorResolver.AuthorInfo(agent.getId(), agent.getName());
        }
        if (requestedAgentId == null || requestedAgentId.isBlank()) {
            throw new IllegalArgumentException("agentId is required when an Owner creates a visa case");
        }
        Agent agent = agentRepository.findByIdAndTenantId(requestedAgentId, principal.tenantId())
                .orElseThrow(() -> new IllegalArgumentException("Agent not found in this agency: " + requestedAgentId));
        return new AuthorResolver.AuthorInfo(agent.getId(), agent.getName());
    }

    private Visa findAccessibleVisa(String id) {
        Visa visa = visaRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Visa case not found: " + id));
        CustomUserPrincipal principal = SecurityContextUtil.getCurrentUserOrThrow();
        if (principal.isAgent() && !visa.getAgentId().equals(principal.userId())) {
            throw new AccessDeniedException("This visa case is not assigned to you");
        }
        return visa;
    }

    private VisaResponse toResponse(Visa v) {
        return VisaResponse.builder()
                .id(v.getId()).customerId(v.getCustomerId()).customerName(v.getCustomerName())
                .agentId(v.getAgentId()).agentName(v.getAgentName()).leadId(v.getLeadId())
                .country(v.getCountry()).visaType(v.getVisaType()).passportNumber(v.getPassportNumber())
                .status(v.getStatus()).passportCollected(v.getPassportCollected())
                .photosCollected(v.getPhotosCollected()).formsFilled(v.getFormsFilled())
                .appointmentDate(v.getAppointmentDate()).biometricsDone(v.getBiometricsDone())
                .submittedToEmbassy(v.getSubmittedToEmbassy()).approved(v.getApproved())
                .rejected(v.getRejected()).passportReturned(v.getPassportReturned())
                .visaValidity(v.getVisaValidity()).expiryDate(v.getExpiryDate())
                .applicationDate(v.getApplicationDate())
                .build();
    }
}
