package com.voyra.crm.service;

import com.voyra.crm.dto.AuditChange;
import com.voyra.crm.dto.PagedResponse;
import com.voyra.crm.dto.VisaChecklistUpdateRequest;
import com.voyra.crm.dto.VisaCreateRequest;
import com.voyra.crm.dto.VisaDashboardSummaryResponse;
import com.voyra.crm.dto.VisaFileResponse;
import com.voyra.crm.dto.VisaResponse;
import com.voyra.crm.dto.VisaValuesUpdateRequest;
import com.voyra.crm.entity.Agent;
import com.voyra.crm.entity.Client;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.entity.Member;
import com.voyra.crm.entity.Vendor;
import com.voyra.crm.entity.Visa;
import com.voyra.crm.entity.VisaField;
import com.voyra.crm.entity.VisaFile;
import com.voyra.crm.enums.VisaFieldType;
import com.voyra.crm.exception.BookingConflictException;
import com.voyra.crm.exception.FieldValidationException;
import com.voyra.crm.exception.NotFoundException;
import com.voyra.crm.repository.MemberRepository;
import com.voyra.crm.repository.VendorRepository;
import com.voyra.crm.repository.VisaFileRepository;
import com.voyra.crm.util.UploadValidator;
import com.voyra.crm.util.VisaFieldValues;
import org.springframework.core.io.Resource;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import com.voyra.crm.enums.AuditEntityType;
import com.voyra.crm.enums.ServiceType;
import com.voyra.crm.enums.VisaStatus;
import com.voyra.crm.repository.AgentRepository;
import com.voyra.crm.repository.ClientRepository;
import com.voyra.crm.repository.TenantRepository;
import com.voyra.crm.repository.VisaRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import com.voyra.crm.security.SecurityContextUtil;
import com.voyra.crm.util.AuditSnapshot;
import com.voyra.crm.util.UniqueIdResolver;
import com.voyra.crm.util.VisaStatusCalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import com.voyra.crm.models.VisaListFilter;
import com.voyra.crm.repository.spec.VisaSpecifications;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class VisaService {

    /** The case's own facts edited in place on the sheet. */
    private static final String[] OWN_FACTS = {
            "passportNumber", "passportExpiry", "nationality", "purchasePrice", "purchaseCurrency", "sellingPrice",
            "vendorName", "remarks"
    };

    private static final String[] AUDITED = {
            "country", "visaType", "passportNumber", "status", "passportCollected",
            "photosCollected", "formsFilled", "appointmentDate", "biometricsDone",
            "submittedToEmbassy", "approved", "rejected", "passportReturned",
            "visaValidity", "expiryDate"
    };

    private final VisaRepository visaRepository;
    private final ClientRepository clientRepository;
    private final AgentRepository agentRepository;
    private final TenantRepository tenantRepository;
    private final AuditService auditService;
    private final MemberRepository memberRepository;
    private final VendorRepository vendorRepository;
    private final VisaFieldService visaFieldService;
    private final VisaFileRepository visaFileRepository;
    private final FileStorageService fileStorage;
    private final AuthorResolver authorResolver;

    static final String FILE_CATEGORY = "visa-files";
    static final long MAX_FILE_BYTES = 10L * 1024 * 1024;

    /** The file held for a File field, ready to download. */
    public record StoredVisaFile(Resource resource, String fileName, String contentType) {
    }

    @Transactional
    public VisaResponse createVisa(VisaCreateRequest request) {
        return createVisa(request, Map.of());
    }

    /**
     * Opens a case for one traveller with the configured fields of its country. Every mandatory field must be filled
     * (a File field by a file part named by its id). Files are checked first, stored only once the row is valid,
     * and deleted again if the transaction rolls back.
     */
    @Transactional
    public VisaResponse createVisa(VisaCreateRequest request, Map<String, MultipartFile> files) {
        AuthorResolver.AuthorInfo owner = resolveOwningAgent();
        Client client = clientRepository.findById(request.getClientId())
                .orElseThrow(() -> new IllegalArgumentException("Client not found: " + request.getClientId()));
        Member member = memberRepository.findById(request.getMemberId())
                .filter(m -> client.getId().equals(m.getClientId()))
                .orElseThrow(() -> new FieldValidationException("memberId", "That traveller is not one of this client's"));
        Map<String, MultipartFile> given = withContent(files);
        given.values().forEach(file -> UploadValidator.assertAllowed(file, MAX_FILE_BYTES));
        List<VisaField> asked = visaFieldService.activeFor(request.getCountry());
        Map<String, String> values = VisaFieldValues.check(request.getValues() == null ? Map.of() : request.getValues(),
                byId(asked), given.keySet(), asked.stream().filter(VisaField::isMandatory).toList());
        values.values().removeIf(String::isEmpty);
        requireCurrencyWithPrice(request.getPurchasePrice(), request.getPurchaseCurrency());

        Visa visa = Visa.builder()
                .id(UniqueIdResolver.resolve(visaRepository::existsById))
                .clientId(client.getId())
                .clientName(client.getName())
                .agentId(owner.id())
                .agentName(owner.name())
                .leadId(request.getLeadId())
                .country(request.getCountry())
                .visaType(request.getVisaType())
                .passportNumber(blankToNull(firstNonBlank(request.getPassportNumber(), member.getPassportNumber())))
                .passportExpiry(request.getPassportExpiry() != null ? request.getPassportExpiry() : member.getPassportExpiry())
                .nationality(blankToNull(firstNonBlank(request.getNationality(), member.getNationality())))
                .memberId(member.getMemberId())
                .memberName(member.getName())
                .fieldValues(new LinkedHashMap<>(values))
                .purchasePrice(request.getPurchasePrice())
                .purchaseCurrency(request.getPurchaseCurrency())
                .sellingPrice(request.getSellingPrice())
                .remarks(blankToNull(request.getRemarks()))
                .applicationDate(request.getApplicationDate() != null ? request.getApplicationDate() : java.time.LocalDate.now())
                .status(VisaStatus.DOCUMENTS_PENDING)
                .createdBy(owner.id())
                .build();
        setVendor(visa, request.getVendorId());
        visa = visaRepository.saveAndFlush(visa);
        List<VisaFile> stored = new ArrayList<>();
        for (Map.Entry<String, MultipartFile> part : given.entrySet()) {
            stored.add(storeFile(visa, part.getKey(), part.getValue()));
        }
        auditService.recordCreate(AuditEntityType.VISA, visa.getId(), labelFor(visa));

        log.info("Visa case created: visaId={}, clientId={}, files={}", visa.getId(), client.getId(), stored.size());
        return toResponse(visa, stored);
    }

    /**
     * Edits the configured fields and the case's own facts in place. Only what is sent is checked: a mandatory field
     * cannot be cleared, and a field made mandatory after the case was opened is not required until it is edited.
     */
    @Transactional
    public VisaResponse updateValues(String id, VisaValuesUpdateRequest request) {
        Visa visa = findAccessibleVisa(id);
        if (!Objects.equals(visa.getVersion(), request.getVersion())) {
            throw new BookingConflictException("VISA_CHANGED", "Someone else changed this application; it has been reloaded",
                    Map.of("visa", toResponse(visa, visaFileRepository.findByVisaId(id))));
        }
        List<AuditChange> changes = new ArrayList<>();
        if (request.getValues() != null && !request.getValues().isEmpty()) {
            Map<String, VisaField> asked = byId(visaFieldService.activeFor(visa.getCountry()));
            List<VisaField> mandatorySent = request.getValues().keySet().stream()
                    .map(asked::get).filter(f -> f != null && f.isMandatory()).toList();
            Map<String, String> values = VisaFieldValues.check(request.getValues(), asked, Set.of(), mandatorySent);
            Map<String, String> merged = new LinkedHashMap<>(visa.getFieldValues());
            values.forEach((fieldId, value) -> {
                String old = merged.get(fieldId);
                String next = value.isEmpty() ? null : value;
                if (!Objects.equals(old, next)) {
                    changes.add(change(asked.get(fieldId).getLabel(), old, next));
                    if (next == null) merged.remove(fieldId); else merged.put(fieldId, next);
                }
            });
            visa.setFieldValues(merged);
        }
        requireCurrencyWithPrice(request.getPurchasePrice() != null ? request.getPurchasePrice() : visa.getPurchasePrice(),
                request.getPurchaseCurrency() != null ? request.getPurchaseCurrency() : visa.getPurchaseCurrency());
        Map<String, String> before = AuditSnapshot.of(visa, OWN_FACTS);
        if (request.getPassportNumber() != null) visa.setPassportNumber(blankToNull(request.getPassportNumber()));
        if (request.getPassportExpiry() != null) visa.setPassportExpiry(request.getPassportExpiry());
        if (request.getNationality() != null) visa.setNationality(blankToNull(request.getNationality()));
        if (request.getPurchasePrice() != null) visa.setPurchasePrice(request.getPurchasePrice());
        if (request.getPurchaseCurrency() != null) visa.setPurchaseCurrency(request.getPurchaseCurrency());
        if (request.getSellingPrice() != null) visa.setSellingPrice(request.getSellingPrice());
        if (request.getVendorId() != null) setVendor(visa, request.getVendorId());
        if (request.getRemarks() != null) visa.setRemarks(blankToNull(request.getRemarks()));
        changes.addAll(AuditSnapshot.diff(before, AuditSnapshot.of(visa, OWN_FACTS)));
        if (changes.isEmpty()) {
            return toResponse(visa, visaFileRepository.findByVisaId(id));
        }
        touch(visa);
        visa = visaRepository.saveAndFlush(visa);
        auditService.recordUpdate(AuditEntityType.VISA, visa.getId(), labelFor(visa), changes);
        log.info("Visa values updated: visaId={}, changes={}", id, changes.size());
        return toResponse(visa, visaFileRepository.findByVisaId(id));
    }

    /** Uploads or replaces the file of one File field; the replaced file is deleted once the change commits. */
    @Transactional
    public VisaFileResponse uploadFile(String id, String fieldId, MultipartFile file) {
        Visa visa = findAccessibleVisa(id);
        VisaField field = fileField(visa, fieldId);
        if (file == null || file.isEmpty()) {
            throw new FieldValidationException("file", "Choose a file to upload");
        }
        UploadValidator.assertAllowed(file, MAX_FILE_BYTES);
        VisaFile old = visaFileRepository.findByVisaIdAndFieldId(id, fieldId).orElse(null);
        if (old != null) {
            visaFileRepository.delete(old);
            visaFileRepository.flush();
            deleteAfterCommit(old.getFileKey());
        }
        VisaFile stored = storeFile(visa, fieldId, file);
        auditService.recordUpdate(AuditEntityType.VISA, id, labelFor(visa),
                List.of(change(field.getLabel(), old == null ? null : old.getFileName(), stored.getFileName())));
        return toFileResponse(stored);
    }

    @Transactional(readOnly = true)
    public StoredVisaFile file(String id, String fieldId) {
        findAccessibleVisa(id);
        VisaFile file = visaFileRepository.findByVisaIdAndFieldId(id, fieldId)
                .orElseThrow(() -> new NotFoundException("No file for this field"));
        return new StoredVisaFile(fileStorage.retrieve(file.getFileKey()), file.getFileName(), file.getFileType());
    }

    /** Removes the file of an optional File field; a mandatory one can only be replaced. */
    @Transactional
    public void deleteFile(String id, String fieldId) {
        Visa visa = findAccessibleVisa(id);
        VisaFile file = visaFileRepository.findByVisaIdAndFieldId(id, fieldId)
                .orElseThrow(() -> new NotFoundException("No file for this field"));
        VisaField field = visaFieldService.byIds(List.of(fieldId)).get(fieldId);
        if (field.isActive() && field.isMandatory()) {
            throw new FieldValidationException(VisaFieldValues.key(fieldId), "This file is mandatory; upload another one instead");
        }
        visaFileRepository.delete(file);
        deleteAfterCommit(file.getFileKey());
        auditService.recordUpdate(AuditEntityType.VISA, id, labelFor(visa), List.of(change(field.getLabel(), file.getFileName(), null)));
    }

    private VisaField fileField(Visa visa, String fieldId) {
        VisaField field = byId(visaFieldService.activeFor(visa.getCountry())).get(fieldId);
        if (field == null || field.getType() != VisaFieldType.FILE) {
            throw new FieldValidationException(VisaFieldValues.key(fieldId), "This field takes no file");
        }
        return field;
    }

    private VisaFile storeFile(Visa visa, String fieldId, MultipartFile file) {
        String tenantId = SecurityContextUtil.getCurrentUserOrThrow().tenantId();
        String key = fileStorage.store(tenantId, FILE_CATEGORY, visa.getId(), file);
        deleteIfRolledBack(key);
        AuthorResolver.AuthorInfo by = authorResolver.resolveCurrentAuthor();
        return visaFileRepository.save(VisaFile.builder()
                .id(UniqueIdResolver.resolve(visaFileRepository::existsById))
                .visaId(visa.getId()).fieldId(fieldId).fileKey(key)
                .fileName(file.getOriginalFilename()) // UploadValidator refused a file without a name
                .fileType(file.getContentType())
                .uploadedBy(by.id()).uploadedByName(by.name()).uploadedAt(LocalDateTime.now())
                .build());
    }

    private void deleteIfRolledBack(String fileKey) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    fileStorage.delete(fileKey);
                }
            }
        });
    }

    private void deleteAfterCommit(String fileKey) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                fileStorage.delete(fileKey);
            }
        });
    }

    private void setVendor(Visa visa, String vendorId) {
        if (vendorId == null || vendorId.isBlank()) {
            visa.setVendorId(null);
            visa.setVendorName(null);
            return;
        }
        Vendor vendor = vendorRepository.findById(vendorId)
                .orElseThrow(() -> new FieldValidationException("vendorId", "Supplier not found"));
        visa.setVendorId(vendor.getId());
        visa.setVendorName(vendor.getName());
    }

    private static void requireCurrencyWithPrice(BigDecimal price, String currency) {
        if (price != null && (currency == null || currency.isBlank())) {
            throw new FieldValidationException("purchaseCurrency", "Pick the purchase currency");
        }
    }

    private static Map<String, MultipartFile> withContent(Map<String, MultipartFile> files) {
        Map<String, MultipartFile> given = new LinkedHashMap<>();
        files.forEach((name, file) -> {
            if (!file.isEmpty()) given.put(name, file);
        });
        return given;
    }

    private static Map<String, VisaField> byId(Collection<VisaField> fields) {
        return fields.stream().collect(Collectors.toMap(VisaField::getId, Function.identity()));
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a.trim() : b;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static AuditChange change(String field, String from, String to) {
        return AuditChange.builder().field(field).oldValue(from).newValue(to).build();
    }

    @Transactional(readOnly = true)
    public List<VisaResponse> listVisas() {
        CustomUserPrincipal principal = SecurityContextUtil.getCurrentUserOrThrow();
        List<Visa> base = principal.isAgent()
                ? visaRepository.findByAgentId(principal.userId())
                : visaRepository.findAll();
        return withFiles(base);
    }

    /** Built-in columns the sheet sorts on (besides {@code field.<id>}). */
    static final Set<String> SORTABLE = Set.of("caseNo", "createdDate", "clientName", "memberName", "status", "passportExpiry",
            "country", "agentName", "visaType", "sellingPrice");

    /** The sheet's query: what matches, in which order, and the fields it may show (common, then the country's). */
    public record VisaQuery(Specification<Visa> spec, Sort sort, List<VisaField> fields) {
    }

    /** Builds the sheet's query for the caller: an agent only ever sees their own cases. Unknown fields or sorts are 400. */
    @Transactional(readOnly = true)
    public VisaQuery query(VisaListFilter filter, String sort) {
        CustomUserPrincipal principal = SecurityContextUtil.getCurrentUserOrThrow();
        List<VisaField> fields = visaFieldService.activeFor(filter.country());
        Map<String, VisaField> byId = byId(fields);
        Specification<Visa> spec = VisaSpecifications.matching(filter, byId, principal.isAgent() ? principal.userId() : null);
        String[] parts = sort == null || sort.isBlank() ? new String[]{"caseNo", "desc"} : sort.trim().split(",", 2);
        boolean ascending = parts.length < 2 || !parts[1].trim().equalsIgnoreCase("desc");
        String property = parts[0].trim();
        if (property.startsWith("field.")) {
            VisaField field = byId.get(property.substring("field.".length()));
            if (field == null || field.getType() == VisaFieldType.FILE) {
                throw new FieldValidationException("sort", "That column cannot be sorted");
            }
            return new VisaQuery(spec.and(VisaSpecifications.sortedByField(field, ascending)), Sort.unsorted(), fields);
        }
        if (!SORTABLE.contains(property)) {
            throw new FieldValidationException("sort", "That column cannot be sorted");
        }
        Sort order = Sort.by(ascending ? Sort.Direction.ASC : Sort.Direction.DESC, property);
        return new VisaQuery(spec, property.equals("caseNo") ? order : order.and(Sort.by(Sort.Direction.DESC, "caseNo")), fields);
    }

    /** One page of the sheet. */
    @Transactional(readOnly = true)
    public PagedResponse<VisaResponse> searchVisas(VisaListFilter filter, String sort, Pageable pageable) {
        VisaQuery q = query(filter, sort);
        Page<Visa> page = visaRepository.findAll(q.spec(), PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), q.sort()));
        Map<String, List<VisaFile>> files = filesOf(page.getContent());
        return PagedResponse.from(page, v -> toResponse(v, files.getOrDefault(v.getId(), List.of())));
    }

    @Transactional(readOnly = true)
    public VisaResponse getVisa(String id) {
        Visa visa = findAccessibleVisa(id);
        List<VisaFile> files = visaFileRepository.findByVisaId(id);
        VisaResponse response = toResponse(visa, files);
        Set<String> asked = visaFieldService.activeFor(visa.getCountry()).stream().map(VisaField::getId).collect(Collectors.toSet());
        Set<String> held = new java.util.HashSet<>(visa.getFieldValues().keySet());
        files.forEach(f -> held.add(f.getFieldId()));
        held.removeAll(asked);
        Map<String, String> removed = new LinkedHashMap<>();
        visaFieldService.byIds(held).forEach((fieldId, field) -> removed.put(fieldId, field.getLabel()));
        response.setRemovedFields(removed);
        return response;
    }

    private List<VisaResponse> withFiles(List<Visa> visas) {
        Map<String, List<VisaFile>> files = filesOf(visas);
        return visas.stream().map(v -> toResponse(v, files.getOrDefault(v.getId(), List.of()))).toList();
    }

    Map<String, List<VisaFile>> filesOf(List<Visa> visas) {
        if (visas.isEmpty()) {
            return Map.of();
        }
        return visaFileRepository.findByVisaIdIn(visas.stream().map(Visa::getId).toList()).stream()
                .collect(Collectors.groupingBy(VisaFile::getVisaId));
    }

    @Transactional
    public VisaResponse updateChecklist(String id, VisaChecklistUpdateRequest request) {
        Visa visa = findAccessibleVisa(id);
        Map<String, String> before = AuditSnapshot.of(visa, AUDITED);

        if (request.getPassportCollected() != null) visa.setPassportCollected(request.getPassportCollected());
        if (request.getPhotosCollected() != null) visa.setPhotosCollected(request.getPhotosCollected());
        if (request.getFormsFilled() != null) visa.setFormsFilled(request.getFormsFilled());
        if (request.getAppointmentDate() != null) visa.setAppointmentDate(request.getAppointmentDate());
        if (request.getBiometricsDone() != null) visa.setBiometricsDone(request.getBiometricsDone());
        if (request.getSubmittedToEmbassy() != null) visa.setSubmittedToEmbassy(request.getSubmittedToEmbassy());
        if (request.getPassportReturned() != null) visa.setPassportReturned(request.getPassportReturned());
        if (request.getVisaValidity() != null) visa.setVisaValidity(request.getVisaValidity());
        if (request.getExpiryDate() != null) visa.setExpiryDate(request.getExpiryDate());

        // Approved/Rejected are mutually exclusive outcomes: refuse both, otherwise the one ticked clears the other.
        if (Boolean.TRUE.equals(request.getApproved()) && Boolean.TRUE.equals(request.getRejected())) {
            throw new IllegalArgumentException("A visa can't be both approved and rejected - tick only one");
        }
        if (request.getApproved() != null) visa.setApproved(request.getApproved());
        if (request.getRejected() != null) visa.setRejected(request.getRejected());
        if (Boolean.TRUE.equals(request.getApproved())) visa.setRejected(false);
        if (Boolean.TRUE.equals(request.getRejected())) visa.setApproved(false);
        if (visa.getExpiryDate() != null && visa.getAppointmentDate() != null
                && visa.getExpiryDate().isBefore(visa.getAppointmentDate())) {
            throw new IllegalArgumentException("Visa expiry date can't be before the appointment date");
        }

        visa.setStatus(VisaStatusCalculator.calculate(visa));
        List<AuditChange> changes = AuditSnapshot.diff(before, AuditSnapshot.of(visa, AUDITED));
        touch(visa);
        visa = visaRepository.saveAndFlush(visa);
        auditService.recordUpdate(AuditEntityType.VISA, visa.getId(), labelFor(visa), changes);
        log.info("Visa checklist updated: visaId={}, status={}", id, visa.getStatus());
        return toResponse(visa, visaFileRepository.findByVisaId(id));
    }

    private void touch(Visa visa) {
        visa.setUpdatedAt(LocalDateTime.now());
        visa.setUpdatedBy(SecurityContextUtil.getCurrentUserOrThrow().userId());
    }

    private String labelFor(Visa v) {
        return v.getClientName() + " / " + v.getCountry();
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

    private AuthorResolver.AuthorInfo resolveOwningAgent() {
        CustomUserPrincipal principal = SecurityContextUtil.getCurrentUserOrThrow();
        if (principal.isAgent()) {
            Agent agent = agentRepository.findById(principal.userId())
                    .orElseThrow(() -> new IllegalStateException("Agent not found: " + principal.userId()));
            if (agent.getManageableServices() == null || !agent.getManageableServices().contains(ServiceType.VISA)) {
                throw new AccessDeniedException("You don't handle visas - only agents who do can open a visa case");
            }
            return new AuthorResolver.AuthorInfo(agent.getId(), agent.getName());
        }
        // Owner: the case is theirs, as with bookings; any requested agent is ignored.
        Tenant tenant = tenantRepository.findById(principal.tenantId())
                .orElseThrow(() -> new IllegalStateException("Agency not found: " + principal.tenantId()));
        return new AuthorResolver.AuthorInfo(tenant.getId(), tenant.getOwnerName());
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

    private static VisaFileResponse toFileResponse(VisaFile f) {
        return VisaFileResponse.builder().fieldId(f.getFieldId()).fileName(f.getFileName()).fileType(f.getFileType())
                .uploadedByName(f.getUploadedByName()).uploadedAt(f.getUploadedAt()).build();
    }

    private VisaResponse toResponse(Visa v, List<VisaFile> files) {
        return VisaResponse.builder()
                .id(v.getId()).clientId(v.getClientId()).clientName(v.getClientName())
                .agentId(v.getAgentId()).agentName(v.getAgentName()).leadId(v.getLeadId())
                .country(v.getCountry()).visaType(v.getVisaType()).passportNumber(v.getPassportNumber())
                .status(v.getStatus()).passportCollected(v.getPassportCollected())
                .photosCollected(v.getPhotosCollected()).formsFilled(v.getFormsFilled())
                .appointmentDate(v.getAppointmentDate()).biometricsDone(v.getBiometricsDone())
                .submittedToEmbassy(v.getSubmittedToEmbassy()).approved(v.getApproved())
                .rejected(v.getRejected()).passportReturned(v.getPassportReturned())
                .visaValidity(v.getVisaValidity()).expiryDate(v.getExpiryDate())
                .applicationDate(v.getApplicationDate())
                .createdAt(v.getCreatedDate())
                .caseNo(v.getCaseNo()).memberId(v.getMemberId()).memberName(v.getMemberName())
                .passportExpiry(v.getPassportExpiry()).nationality(v.getNationality())
                .values(v.getFieldValues() == null ? Map.of() : v.getFieldValues())
                .files(files.stream().map(VisaService::toFileResponse).toList())
                .purchasePrice(v.getPurchasePrice()).purchaseCurrency(v.getPurchaseCurrency())
                .sellingPrice(v.getSellingPrice()).vendorId(v.getVendorId()).vendorName(v.getVendorName())
                .remarks(v.getRemarks()).version(v.getVersion())
                .build();
    }
}
