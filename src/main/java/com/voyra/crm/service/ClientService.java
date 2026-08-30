package com.voyra.crm.service;

import com.voyra.crm.dto.ClientCreateRequest;
import com.voyra.crm.dto.ClientDetailResponse;
import com.voyra.crm.dto.ClientLookupResponse;
import com.voyra.crm.dto.ClientResponse;
import com.voyra.crm.dto.ClientUpdateRequest;
import com.voyra.crm.dto.MemberResponse;
import com.voyra.crm.dto.PagedResponse;
import com.voyra.crm.entity.Agent;
import com.voyra.crm.entity.Client;
import com.voyra.crm.entity.Member;
import com.voyra.crm.enums.ClientType;
import com.voyra.crm.enums.MemberRelation;
import com.voyra.crm.enums.MemberType;
import com.voyra.crm.repository.AgentRepository;
import com.voyra.crm.repository.BookingRepository;
import com.voyra.crm.repository.ClientInvoiceRepository;
import com.voyra.crm.repository.ClientRepository;
import com.voyra.crm.repository.LeadRepository;
import com.voyra.crm.repository.MemberRepository;
import com.voyra.crm.repository.VisaRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import com.voyra.crm.security.SecurityContextUtil;
import com.voyra.crm.util.UniqueIdResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ClientService {

    private final ClientRepository clientRepository;
    private final MemberRepository memberRepository;
    private final LeadRepository leadRepository;
    private final BookingRepository bookingRepository;
    private final VisaRepository visaRepository;
    private final ClientInvoiceRepository clientInvoiceRepository;
    private final AgentRepository agentRepository;
    private final MemberMapper memberMapper;

    /**
     * Creates the client and its primary member together.
     *
     * <p>They are one operation on purpose: a client with no primary member has nobody to call
     * and no source for the {@code client_name} snapshot every downstream table carries. The
     * partial unique index on {@code member (client_id) WHERE type = 'CLIENT'} makes that
     * invariant structural rather than a convention.
     */
    @Transactional
    public ClientDetailResponse createClient(ClientCreateRequest request) {
        AuthorResolver.AuthorInfo owner = resolveOwningAgent(request.getAgentId());

        if (clientRepository.existsByIdentifierAndIsActiveTrue(request.getIdentifier())) {
            throw new IllegalStateException("A client already exists with identifier " + request.getIdentifier());
        }

        Client client = Client.builder()
                .id(UniqueIdResolver.resolve(clientRepository::existsById))
                .identifier(request.getIdentifier())
                .name(request.getName())
                .type(request.getType())
                .agentId(owner.id())
                .agentName(owner.name())
                .isActive(true)
                .createdAt(LocalDateTime.now())
                .createdBy(currentUserId())
                .build();
        clientRepository.save(client);

        Member primary = Member.builder()
                .memberId(UniqueIdResolver.resolve(memberRepository::existsById))
                .clientId(client.getId())
                .name(request.getPrimaryMemberName())
                .email(request.getPrimaryMemberEmail())
                .countryCode(request.getPrimaryMemberCountryCode())
                .phone(request.getPrimaryMemberPhone())
                .type(MemberType.CLIENT)
                .relation(MemberRelation.SELF)
                .dob(request.getPrimaryMemberDob())
                .gender(request.getPrimaryMemberGender())
                .nationality(request.getPrimaryMemberNationality())
                .passportNumber(request.getPrimaryMemberPassportNumber())
                .passportExpiry(request.getPrimaryMemberPassportExpiry())
                .isActive(true)
                .createdAt(LocalDateTime.now())
                .createdBy(currentUserId())
                .build();
        memberRepository.save(primary);

        log.info("Client created: clientId={}, type={}, agentId={}", client.getId(), client.getType(), owner.id());
        return toDetailResponse(client);
    }

    @Transactional(readOnly = true)
    public List<ClientResponse> listClients(ClientType typeFilter) {
        CustomUserPrincipal principal = SecurityContextUtil.getCurrentUserOrThrow();
        List<Client> clients = principal.isAgent()
                ? clientRepository.findByAgentId(principal.userId())
                : clientRepository.findAll();
        return clients.stream()
                .filter(c -> typeFilter == null || c.getType() == typeFilter)
                .map(this::toResponse)
                .toList();
    }

    /**
     * Every combination resolves to an indexed derived query - never a full table read filtered
     * in Java. Agent callers are structurally confined to the clients they own.
     */
    @Transactional(readOnly = true)
    public PagedResponse<ClientResponse> listClients(ClientType typeFilter, Pageable pageable) {
        CustomUserPrincipal principal = SecurityContextUtil.getCurrentUserOrThrow();
        Page<Client> page;
        if (principal.isAgent()) {
            page = typeFilter == null
                    ? clientRepository.findByAgentId(principal.userId(), pageable)
                    : clientRepository.findByAgentIdAndType(principal.userId(), typeFilter, pageable);
        } else {
            page = typeFilter == null
                    ? clientRepository.findAll(pageable)
                    : clientRepository.findByType(typeFilter, pageable);
        }
        return PagedResponse.from(page, this::toResponse);
    }

    @Transactional(readOnly = true)
    public ClientDetailResponse getClient(String id) {
        return toDetailResponse(findAccessibleClient(id));
    }

    /**
     * Duplicate check for the Add Lead wizard.
     *
     * <p>Deliberately searches the whole agency rather than the caller's own clients. An agent
     * who cannot see that a walk-in already exists under a colleague will create a second
     * account for the same person, and the agency ends up unable to answer "what has this
     * customer booked before". The response therefore names the owning agent rather than
     * pretending the client does not exist.
     */
    @Transactional(readOnly = true)
    public ClientLookupResponse lookupByIdentifier(String identifier) {
        return clientRepository.findByIdentifierAndIsActiveTrue(identifier)
                .map(c -> ClientLookupResponse.builder()
                        .found(true)
                        .id(c.getId())
                        .identifier(c.getIdentifier())
                        .name(c.getName())
                        .type(c.getType())
                        .agentName(c.getAgentName())
                        .memberCount((int) memberRepository.countByClientIdAndIsActiveTrue(c.getId()))
                        .build())
                .orElseGet(() -> ClientLookupResponse.builder().found(false).identifier(identifier).build());
    }

    /**
     * Renaming a client re-syncs the {@code client_name} snapshot on every lead, booking, visa
     * case and invoice in the same transaction. Those snapshots exist so list screens are a
     * single flat SELECT; letting them drift would show the old name on half the app.
     */
    @Transactional
    public ClientDetailResponse updateClient(String id, ClientUpdateRequest request) {
        Client client = findAccessibleClient(id);

        if (request.getIdentifier() != null && !request.getIdentifier().equals(client.getIdentifier())) {
            if (clientRepository.existsByIdentifierAndIsActiveTrue(request.getIdentifier())) {
                throw new IllegalStateException("A client already exists with identifier " + request.getIdentifier());
            }
            client.setIdentifier(request.getIdentifier());
        }
        if (request.getType() != null) {
            client.setType(request.getType());
        }
        if (request.getAgentId() != null) {
            CustomUserPrincipal principal = SecurityContextUtil.getCurrentUserOrThrow();
            if (principal.isAgent()) {
                throw new AccessDeniedException("Only the Agency Owner can reassign a client");
            }
            Agent agent = agentRepository.findByIdAndTenantId(request.getAgentId(), principal.tenantId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Agent not found in this agency: " + request.getAgentId()));
            client.setAgentId(agent.getId());
            client.setAgentName(agent.getName());
        }

        boolean renamed = request.getName() != null && !request.getName().equals(client.getName());
        if (renamed) {
            client.setName(request.getName());
        }

        client.setModifiedAt(LocalDateTime.now());
        client.setModifiedBy(currentUserId());
        clientRepository.save(client);

        if (renamed) {
            leadRepository.updateClientNameForClient(client.getId(), client.getName());
            bookingRepository.updateClientNameForClient(client.getId(), client.getName());
            visaRepository.updateClientNameForClient(client.getId(), client.getName());
            clientInvoiceRepository.updateClientNameForClient(client.getId(), client.getName());
        }

        log.info("Client updated: clientId={}, renamed={}", id, renamed);
        return toDetailResponse(client);
    }

    /**
     * Deactivation, not deletion.
     *
     * <p>Bookings, invoices and visa cases reference the client id and must keep resolving.
     * Deactivating frees the identifier for reuse - the unique index is scoped to active rows -
     * which is what lets a client be re-created after a merge or a data-entry mistake.
     */
    @Transactional
    public ClientDetailResponse updateStatus(String id, boolean active) {
        Client client = findAccessibleClient(id);
        if (!active && leadRepository.countByClientIdAndStatusNotIn(id, LeadService.TERMINAL_STATUSES) > 0) {
            throw new IllegalStateException("This client still has open leads - close or reassign them first");
        }
        client.setIsActive(active);
        client.setModifiedAt(LocalDateTime.now());
        client.setModifiedBy(currentUserId());
        try {
            clientRepository.save(client);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalStateException("Another active client already uses identifier " + client.getIdentifier());
        }
        log.info("Client status updated: clientId={}, active={}", id, active);
        return toDetailResponse(client);
    }

    /** Shared ownership check, reused by MemberService and LeadService. */
    public Client findAccessibleClient(String id) {
        Client client = clientRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Client not found: " + id));
        CustomUserPrincipal principal = SecurityContextUtil.getCurrentUserOrThrow();
        if (principal.isAgent() && !client.getAgentId().equals(principal.userId())) {
            throw new AccessDeniedException("This client does not belong to you");
        }
        return client;
    }

    private AuthorResolver.AuthorInfo resolveOwningAgent(String requestedAgentId) {
        CustomUserPrincipal principal = SecurityContextUtil.getCurrentUserOrThrow();
        if (principal.isAgent()) {
            Agent agent = agentRepository.findById(principal.userId())
                    .orElseThrow(() -> new IllegalStateException("Agent not found: " + principal.userId()));
            return new AuthorResolver.AuthorInfo(agent.getId(), agent.getName());
        }
        if (requestedAgentId == null || requestedAgentId.isBlank()) {
            throw new IllegalArgumentException("agentId is required when an Owner creates a client");
        }
        Agent agent = agentRepository.findByIdAndTenantId(requestedAgentId, principal.tenantId())
                .orElseThrow(() -> new IllegalArgumentException("Agent not found in this agency: " + requestedAgentId));
        return new AuthorResolver.AuthorInfo(agent.getId(), agent.getName());
    }

    private String currentUserId() {
        return SecurityContextUtil.getCurrentUserOrThrow().userId();
    }

    private ClientResponse toResponse(Client client) {
        return ClientResponse.builder()
                .id(client.getId()).identifier(client.getIdentifier()).name(client.getName())
                .type(client.getType()).agentId(client.getAgentId()).agentName(client.getAgentName())
                .memberCount((int) memberRepository.countByClientIdAndIsActiveTrue(client.getId()))
                .isActive(client.getIsActive())
                .createdAt(client.getCreatedAt()).createdBy(client.getCreatedBy())
                .modifiedAt(client.getModifiedAt()).modifiedBy(client.getModifiedBy())
                .build();
    }

    private ClientDetailResponse toDetailResponse(Client client) {
        List<MemberResponse> members = memberMapper.toResponsesWithDocuments(
                memberRepository.findByClientIdAndIsActiveTrue(client.getId()));
        return ClientDetailResponse.builder()
                .id(client.getId()).identifier(client.getIdentifier()).name(client.getName())
                .type(client.getType()).agentId(client.getAgentId()).agentName(client.getAgentName())
                .isActive(client.getIsActive()).members(members)
                .createdAt(client.getCreatedAt()).createdBy(client.getCreatedBy())
                .modifiedAt(client.getModifiedAt()).modifiedBy(client.getModifiedBy())
                .build();
    }
}
