package com.voyra.crm.service;

import com.voyra.crm.dto.AuditChange;
import com.voyra.crm.dto.VisaFieldOrderRequest;
import com.voyra.crm.dto.VisaFieldRequest;
import com.voyra.crm.dto.VisaFieldResponse;
import com.voyra.crm.entity.VisaField;
import com.voyra.crm.enums.AuditEntityType;
import com.voyra.crm.enums.VisaFieldScope;
import com.voyra.crm.enums.VisaFieldType;
import com.voyra.crm.exception.BookingConflictException;
import com.voyra.crm.exception.FieldValidationException;
import com.voyra.crm.exception.NotFoundException;
import com.voyra.crm.repository.VisaFieldRepository;
import com.voyra.crm.repository.VisaRepository;
import com.voyra.crm.security.SecurityContextUtil;
import com.voyra.crm.util.UniqueIdResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The visa fields an agency asks for: common ones for every country, and per-country ones. Removing a field only
 * deactivates it, and adding back a removed label restores the same row, so applications that hold values keep them.
 */
@Service
@RequiredArgsConstructor
public class VisaFieldService {

    static final int MAX_FIELDS = 40;
    static final int MAX_OPTIONS = 30;
    static final int MAX_OPTION_LENGTH = 40;

    private final VisaFieldRepository repository;
    private final VisaRepository visaRepository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public List<VisaFieldResponse> forCountry(String country) {
        return activeFor(country).stream().map(VisaFieldService::toResponse).toList();
    }

    /** Active common fields, then the active fields of that country; a blank country gives the common ones only. */
    @Transactional(readOnly = true)
    public List<VisaField> activeFor(String country) {
        return repository.findActiveFor(country == null ? "" : country.trim());
    }

    @Transactional(readOnly = true)
    public Map<String, VisaField> byIds(Collection<String> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return repository.findAllById(ids).stream().collect(Collectors.toMap(VisaField::getId, Function.identity()));
    }

    @Transactional(readOnly = true)
    public List<VisaFieldResponse> all() {
        return repository.findByActiveTrueOrderByScopeAscCountryAscSortOrderAsc().stream().map(VisaFieldService::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<String> countries() {
        return repository.findActiveCountries();
    }

    @Transactional
    public VisaFieldResponse create(VisaFieldRequest request) {
        String country = countryOf(request.getScope(), request.getCountry());
        String label = request.getLabel().trim();
        List<String> options = optionsOf(request.getType(), request.getOptions());
        List<VisaField> group = repository.findInGroup(request.getScope(), country);
        rejectTakenLabel(group, label, null);
        if (group.stream().filter(VisaField::isActive).count() >= MAX_FIELDS) {
            throw new IllegalArgumentException("A list can have at most " + MAX_FIELDS + " fields");
        }
        int next = group.stream().filter(VisaField::isActive).mapToInt(VisaField::getSortOrder).max().orElse(-1) + 1;
        LocalDateTime now = LocalDateTime.now();
        String by = SecurityContextUtil.getCurrentUserId().orElse(null);

        VisaField removed = group.stream().filter(f -> !f.isActive() && sameLabel(f, label)).findFirst().orElse(null);
        if (removed != null) {
            if (removed.getType() != request.getType()) {
                throw new FieldValidationException("type", "A field's type cannot change");
            }
            removed.setActive(true);
            removed.setLabel(label);
            removed.setOptions(options);
            removed.setMandatory(request.isMandatory());
            removed.setSortOrder(next);
            removed.setUpdatedAt(now);
            removed.setUpdatedBy(by);
            repository.save(removed);
            auditService.recordUpdate(AuditEntityType.VISA_FIELD, removed.getId(), label,
                    List.of(change("active", false, true)));
            return toResponse(removed);
        }
        VisaField field = repository.save(VisaField.builder()
                .id(UniqueIdResolver.resolve(repository::existsById))
                .scope(request.getScope()).country(country).label(label).type(request.getType()).options(options)
                .mandatory(request.isMandatory()).sortOrder(next).active(true)
                .createdAt(now).createdBy(by)
                .build());
        auditService.recordCreate(AuditEntityType.VISA_FIELD, field.getId(), label);
        return toResponse(field);
    }

    /** Label, options and Mandatory change; scope, country and type stay. An option renamed in place rewrites stored values. */
    @Transactional
    public VisaFieldResponse update(String id, VisaFieldRequest request) {
        VisaField field = active(id);
        if (field.getType() != request.getType()) {
            throw new FieldValidationException("type", "A field's type cannot change");
        }
        String country = field.getScope() == VisaFieldScope.COMMON ? null : request.getCountry();
        if (field.getScope() != request.getScope()
                || !Objects.equals(lower(field.getCountry()), lower(country == null ? null : country.trim()))) {
            throw new IllegalArgumentException("A field cannot move to another list");
        }
        String label = request.getLabel().trim();
        List<String> options = optionsOf(field.getType(), request.getOptions());
        rejectTakenLabel(repository.findInGroup(field.getScope(), field.getCountry()), label, id);

        List<AuditChange> changes = new ArrayList<>();
        if (!label.equals(field.getLabel())) {
            changes.add(change("label", field.getLabel(), label));
        }
        if (request.isMandatory() != field.isMandatory()) {
            changes.add(change("mandatory", field.isMandatory(), request.isMandatory()));
        }
        List<String> before = field.getOptions() == null ? List.of() : field.getOptions();
        if (!before.equals(options)) {
            changes.add(change("options", String.join(", ", before), String.join(", ", options)));
            renameInPlace(id, before, options);
        }
        field.setLabel(label);
        field.setMandatory(request.isMandatory());
        field.setOptions(options);
        field.setUpdatedAt(LocalDateTime.now());
        field.setUpdatedBy(SecurityContextUtil.getCurrentUserId().orElse(null));
        repository.save(field);
        auditService.recordUpdate(AuditEntityType.VISA_FIELD, id, label, changes);
        return toResponse(field);
    }

    @Transactional
    public void delete(String id) {
        VisaField field = active(id);
        field.setActive(false);
        field.setUpdatedAt(LocalDateTime.now());
        field.setUpdatedBy(SecurityContextUtil.getCurrentUserId().orElse(null));
        repository.save(field);
        auditService.recordDelete(AuditEntityType.VISA_FIELD, id, field.getLabel(), List.of(change("active", true, false)));
    }

    @Transactional
    public List<VisaFieldResponse> reorder(VisaFieldOrderRequest request) {
        String country = countryOf(request.getScope(), request.getCountry());
        List<VisaField> active = repository.findInGroup(request.getScope(), country).stream().filter(VisaField::isActive).toList();
        Map<String, VisaField> byId = active.stream().collect(Collectors.toMap(VisaField::getId, Function.identity()));
        if (request.getIds().size() != active.size() || !byId.keySet().equals(new HashSet<>(request.getIds()))) {
            throw new IllegalArgumentException("Give every field of that list once");
        }
        List<VisaField> ordered = new ArrayList<>();
        for (int i = 0; i < request.getIds().size(); i++) {
            VisaField field = byId.get(request.getIds().get(i));
            if (field.getSortOrder() != i) {
                auditService.recordUpdate(AuditEntityType.VISA_FIELD, field.getId(), field.getLabel(),
                        List.of(change("sortOrder", field.getSortOrder(), i)));
                field.setSortOrder(i);
                repository.save(field);
            }
            ordered.add(field);
        }
        return ordered.stream().map(VisaFieldService::toResponse).toList();
    }

    /** Same position, new text, and neither text is elsewhere in the other list: a rename, so stored values follow it. */
    private void renameInPlace(String id, List<String> before, List<String> after) {
        for (int i = 0; i < Math.min(before.size(), after.size()); i++) {
            String old = before.get(i);
            String now = after.get(i);
            if (!old.equals(now) && !after.contains(old) && !before.contains(now)) {
                visaRepository.renameOption(id, old, now);
            }
        }
    }

    private VisaField active(String id) {
        return repository.findById(id).filter(VisaField::isActive)
                .orElseThrow(() -> new NotFoundException("Visa field not found"));
    }

    private static String countryOf(VisaFieldScope scope, String country) {
        if (scope == VisaFieldScope.COMMON) {
            return null;
        }
        if (country == null || country.isBlank()) {
            throw new FieldValidationException("country", "Pick the country");
        }
        return country.trim();
    }

    private static List<String> optionsOf(VisaFieldType type, List<String> raw) {
        List<String> options = raw == null ? List.of() : raw.stream().map(o -> o == null ? "" : o.trim()).toList();
        if (type != VisaFieldType.DROPDOWN) {
            if (!options.isEmpty()) {
                throw new FieldValidationException("options", "Only a Dropdown field takes options");
            }
            return List.of();
        }
        if (options.isEmpty() || options.size() > MAX_OPTIONS) {
            throw new FieldValidationException("options", "A Dropdown needs 1 to " + MAX_OPTIONS + " options");
        }
        Set<String> seen = new HashSet<>();
        for (String option : options) {
            if (option.isEmpty() || option.length() > MAX_OPTION_LENGTH) {
                throw new FieldValidationException("options", "Each option needs 1 to " + MAX_OPTION_LENGTH + " characters");
            }
            if (!seen.add(option.toLowerCase(Locale.ROOT))) {
                throw new FieldValidationException("options", "Option \"" + option + "\" is listed twice");
            }
        }
        return new ArrayList<>(options);
    }

    private static void rejectTakenLabel(List<VisaField> group, String label, String exceptId) {
        boolean taken = group.stream().anyMatch(f -> f.isActive() && !f.getId().equals(exceptId) && sameLabel(f, label));
        if (taken) {
            throw new BookingConflictException("VISA_FIELD_EXISTS", "A field named \"" + label + "\" is already in this list", Map.of());
        }
    }

    private static boolean sameLabel(VisaField field, String label) {
        return field.getLabel().equalsIgnoreCase(label);
    }

    private static String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    private static AuditChange change(String field, Object from, Object to) {
        return AuditChange.builder().field(field).oldValue(String.valueOf(from)).newValue(String.valueOf(to)).build();
    }

    static VisaFieldResponse toResponse(VisaField f) {
        return VisaFieldResponse.builder()
                .id(f.getId()).scope(f.getScope()).country(f.getCountry()).label(f.getLabel()).type(f.getType())
                .options(f.getOptions() == null ? List.of() : f.getOptions())
                .mandatory(f.isMandatory()).sortOrder(f.getSortOrder()).active(f.isActive())
                .build();
    }
}
