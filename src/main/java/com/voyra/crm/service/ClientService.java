package com.voyra.crm.service;

import com.voyra.crm.dto.AuditChange;
import com.voyra.crm.dto.ClientCreateRequest;
import com.voyra.crm.dto.ClientDetailResponse;
import com.voyra.crm.dto.ClientDuplicateCandidateResponse;
import com.voyra.crm.dto.ClientDuplicateCheckRequest;
import com.voyra.crm.dto.ClientLookupResponse;
import com.voyra.crm.dto.ClientResponse;
import com.voyra.crm.dto.ClientUpdateRequest;
import com.voyra.crm.dto.MemberResponse;
import com.voyra.crm.dto.PagedResponse;
import com.voyra.crm.entity.Client;
import com.voyra.crm.entity.Member;
import com.voyra.crm.enums.AuditEntityType;
import com.voyra.crm.enums.ClientType;
import com.voyra.crm.enums.MemberRelation;
import com.voyra.crm.enums.MemberType;
import com.voyra.crm.repository.BookingRepository;
import com.voyra.crm.repository.ClientInvoiceRepository;
import com.voyra.crm.repository.ClientRepository;
import com.voyra.crm.repository.LeadRepository;
import com.voyra.crm.repository.MemberRepository;
import com.voyra.crm.repository.VisaRepository;
import com.voyra.crm.security.SecurityContextUtil;
import com.voyra.crm.util.AuditSnapshot;
import com.voyra.crm.util.UniqueIdResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class ClientService {

    private static final String[] AUDITED = { "identifier", "name", "type", "isActive", "gstin", "stateCode", "billingAddress", "isOverseas" };

    private final ClientRepository clientRepository;
    private final MemberRepository memberRepository;
    private final LeadRepository leadRepository;
    private final BookingRepository bookingRepository;
    private final VisaRepository visaRepository;
    private final ClientInvoiceRepository clientInvoiceRepository;
    private final MemberMapper memberMapper;
    private final AuditService auditService;

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
        if (clientRepository.existsByIdentifierAndIsActiveTrue(request.getIdentifier())) {
            throw new IllegalStateException("A client already exists with identifier " + request.getIdentifier());
        }

        Client client = Client.builder()
                .id(UniqueIdResolver.resolve(clientRepository::existsById))
                .identifier(request.getIdentifier())
                .name(request.getName())
                .type(request.getType())
                .isActive(true)
                .createdAt(LocalDateTime.now())
                .createdBy(currentUserId())
                .possibleDuplicateOf(request.getPossibleDuplicateOf())
                .gstin(request.getGstin())
                .stateCode(request.getStateCode())
                .billingAddress(request.getBillingAddress())
                .isOverseas(Boolean.TRUE.equals(request.getIsOverseas()))
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
        auditService.recordCreate(AuditEntityType.CLIENT, client.getId(), client.getName());

        log.info("Client created: clientId={}, type={}", client.getId(), client.getType());
        return toDetailResponse(client);
    }

    @Transactional(readOnly = true)
    public List<ClientResponse> listClients(ClientType typeFilter) {
        return clientRepository.findAll().stream()
                .filter(c -> typeFilter == null || c.getType() == typeFilter)
                .map(this::toResponse)
                .toList();
    }

    /** Every combination resolves to an indexed derived query - never a full table read filtered in Java. */
    @Transactional(readOnly = true)
    public PagedResponse<ClientResponse> listClients(ClientType typeFilter, Pageable pageable) {
        Page<Client> page = typeFilter == null
                ? clientRepository.findAll(pageable)
                : clientRepository.findByType(typeFilter, pageable);
        return PagedResponse.from(page, this::toResponse);
    }

    @Transactional(readOnly = true)
    public ClientDetailResponse getClient(String id) {
        return toDetailResponse(findAccessibleClient(id));
    }

    /**
     * Duplicate check for the Add Lead wizard, so a walk-in already known to the agency is
     * found instead of a second account being created for the same person.
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
                        .memberCount((int) memberRepository.countByClientIdAndIsActiveTrue(c.getId()))
                        .build())
                .orElseGet(() -> ClientLookupResponse.builder().found(false).identifier(identifier).build());
    }

    /**
     * Pre-save advisory check, beyond the exact-identifier block above: a B2C phone written
     * differently (matched on the last 10 digits) or a name that is merely similar via pg_trgm.
     * Read-only - never blocks; the caller decides whether to proceed and, if so, may pass the
     * chosen candidate's id back as {@code possibleDuplicateOf} on the actual create call.
     */
    @Transactional(readOnly = true)
    public List<ClientDuplicateCandidateResponse> checkDuplicates(ClientDuplicateCheckRequest request) {
        Map<String, ClientDuplicateCandidateResponse> byId = new LinkedHashMap<>();

        if (request.getIdentifier() != null && !request.getIdentifier().isBlank()) {
            clientRepository.findByIdentifierAndIsActiveTrue(request.getIdentifier())
                    .ifPresent(c -> byId.put(c.getId(), toDuplicateCandidate(c, "EXACT_IDENTIFIER")));
            for (Client c : clientRepository.findByPhoneSuffix(request.getIdentifier())) {
                byId.putIfAbsent(c.getId(), toDuplicateCandidate(c, "PHONE_SUFFIX"));
            }
        }
        if (request.getName() != null && !request.getName().isBlank()) {
            for (Client c : clientRepository.findSimilarByName(request.getName())) {
                byId.putIfAbsent(c.getId(), toDuplicateCandidate(c, "NAME_SIMILARITY"));
            }
        }
        return List.copyOf(byId.values());
    }

    /**
     * Renaming a client re-syncs the {@code client_name} snapshot on every lead, booking, visa
     * case and invoice in the same transaction. Those snapshots exist so list screens are a
     * single flat SELECT; letting them drift would show the old name on half the app.
     */
    @Transactional
    public ClientDetailResponse updateClient(String id, ClientUpdateRequest request) {
        Client client = findAccessibleClient(id);
        Map<String, String> before = AuditSnapshot.of(client, AUDITED);

        if (request.getIdentifier() != null && !request.getIdentifier().equals(client.getIdentifier())) {
            if (clientRepository.existsByIdentifierAndIsActiveTrue(request.getIdentifier())) {
                throw new IllegalStateException("A client already exists with identifier " + request.getIdentifier());
            }
            client.setIdentifier(request.getIdentifier());
        }
        if (request.getType() != null) {
            client.setType(request.getType());
        }

        boolean renamed = request.getName() != null && !request.getName().equals(client.getName());
        if (renamed) {
            client.setName(request.getName());
        }
        if (request.getGstin() != null) {
            client.setGstin(request.getGstin());
        }
        if (request.getStateCode() != null) {
            client.setStateCode(request.getStateCode());
        }
        if (request.getBillingAddress() != null) {
            client.setBillingAddress(request.getBillingAddress());
        }
        if (request.getIsOverseas() != null) {
            client.setIsOverseas(request.getIsOverseas());
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

        // The *_name bulk re-sync above is a denormalisation fix-up, not a business change - it
        // is deliberately excluded from the audited diff, which already captured the real edit.
        List<AuditChange> changes = AuditSnapshot.diff(before, AuditSnapshot.of(client, AUDITED));
        auditService.recordUpdate(AuditEntityType.CLIENT, client.getId(), client.getName(), changes);

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
        Map<String, String> before = AuditSnapshot.of(client, AUDITED);
        client.setIsActive(active);
        client.setModifiedAt(LocalDateTime.now());
        client.setModifiedBy(currentUserId());
        try {
            clientRepository.save(client);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalStateException("Another active client already uses identifier " + client.getIdentifier());
        }
        auditService.recordUpdate(AuditEntityType.CLIENT, client.getId(), client.getName(),
                AuditSnapshot.diff(before, AuditSnapshot.of(client, AUDITED)));
        log.info("Client status updated: clientId={}, active={}", id, active);
        return toDetailResponse(client);
    }

    /** Resolves a client by id, shared by MemberService and LeadService. No agent owns a
     *  client, so every signed-in agent and the Owner can reach any client this way. */
    public Client findAccessibleClient(String id) {
        return clientRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Client not found: " + id));
    }

    private String currentUserId() {
        return SecurityContextUtil.getCurrentUserOrThrow().userId();
    }

    private ClientResponse toResponse(Client client) {
        return ClientResponse.builder()
                .id(client.getId()).identifier(client.getIdentifier()).name(client.getName())
                .type(client.getType())
                .memberCount((int) memberRepository.countByClientIdAndIsActiveTrue(client.getId()))
                .isActive(client.getIsActive())
                .createdAt(client.getCreatedAt()).createdBy(client.getCreatedBy())
                .modifiedAt(client.getModifiedAt()).modifiedBy(client.getModifiedBy())
                .possibleDuplicateOf(client.getPossibleDuplicateOf())
                .gstin(client.getGstin()).stateCode(client.getStateCode())
                .billingAddress(client.getBillingAddress()).isOverseas(client.getIsOverseas())
                .build();
    }

    private ClientDetailResponse toDetailResponse(Client client) {
        List<MemberResponse> members = memberMapper.toResponsesWithDocuments(
                memberRepository.findByClientIdAndIsActiveTrue(client.getId()));
        return ClientDetailResponse.builder()
                .id(client.getId()).identifier(client.getIdentifier()).name(client.getName())
                .type(client.getType())
                .isActive(client.getIsActive()).members(members)
                .createdAt(client.getCreatedAt()).createdBy(client.getCreatedBy())
                .modifiedAt(client.getModifiedAt()).modifiedBy(client.getModifiedBy())
                .possibleDuplicateOf(client.getPossibleDuplicateOf())
                .gstin(client.getGstin()).stateCode(client.getStateCode())
                .billingAddress(client.getBillingAddress()).isOverseas(client.getIsOverseas())
                .build();
    }

    private ClientDuplicateCandidateResponse toDuplicateCandidate(Client c, String reason) {
        return ClientDuplicateCandidateResponse.builder()
                .id(c.getId()).name(c.getName()).identifier(c.getIdentifier()).type(c.getType())
                .matchReason(reason)
                .build();
    }
}
