package com.voyra.crm.service;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VisaFieldServiceTest {

    @Mock private VisaFieldRepository repository;
    @Mock private VisaRepository visaRepository;
    @Mock private AuditService auditService;
    @InjectMocks private VisaFieldService service;

    private final List<VisaField> group = new ArrayList<>();

    @BeforeEach
    void setUp() {
        given(repository.findInGroup(any(), any())).willAnswer(inv -> group);
        given(repository.save(any(VisaField.class))).willAnswer(inv -> inv.getArgument(0));
        given(repository.existsById(anyString())).willReturn(false);
    }

    private static VisaFieldRequest req(VisaFieldScope scope, String country, String label, VisaFieldType type, List<String> options) {
        return VisaFieldRequest.builder().scope(scope).country(country).label(label).type(type).options(options).mandatory(true).build();
    }

    private static VisaField field(String id, VisaFieldScope scope, String country, String label, VisaFieldType type, boolean active, int order) {
        return VisaField.builder().id(id).scope(scope).country(country).label(label).type(type).active(active).sortOrder(order)
                .options(type == VisaFieldType.DROPDOWN ? new ArrayList<>(List.of("RECVD", "PENDING")) : null)
                .createdAt(LocalDateTime.now()).build();
    }

    @Test
    void createsACommonAndACountryField() {
        VisaFieldResponse common = service.create(req(VisaFieldScope.COMMON, null, "  Passport copy ", VisaFieldType.FILE, null));
        assertThat(common.getLabel()).isEqualTo("Passport copy");
        assertThat(common.getCountry()).isNull();
        assertThat(common.isActive()).isTrue();
        assertThat(common.isMandatory()).isTrue();
        assertThat(common.getOptions()).isEmpty();

        group.add(field("x", VisaFieldScope.COUNTRY, "Kenya", "EVISA", VisaFieldType.TEXT, true, 4));
        VisaFieldResponse country = service.create(req(VisaFieldScope.COUNTRY, " Kenya ", "Medical", VisaFieldType.DROPDOWN, List.of(" RECVD ", "PENDING")));
        assertThat(country.getCountry()).isEqualTo("Kenya");
        assertThat(country.getOptions()).containsExactly("RECVD", "PENDING");
        assertThat(country.getSortOrder()).isEqualTo(5);
        verify(auditService).recordCreate(eq(AuditEntityType.VISA_FIELD), eq(country.getId()), eq("Medical"));
    }

    @Test
    void countryFieldNeedsACountry() {
        assertThatThrownBy(() -> service.create(req(VisaFieldScope.COUNTRY, "  ", "Medical", VisaFieldType.TEXT, null)))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> {
                    assertThat(e.getField()).isEqualTo("country");
                    assertThat(e.getMessage()).isEqualTo("Pick the country");
                });
        assertThatThrownBy(() -> service.create(req(VisaFieldScope.COUNTRY, null, "Medical", VisaFieldType.TEXT, null)))
                .isInstanceOf(FieldValidationException.class);
    }

    @Test
    void commonFieldTakesNoCountry() {
        assertThat(service.create(req(VisaFieldScope.COMMON, "Kenya", "Nationality", VisaFieldType.TEXT, null)).getCountry()).isNull();
    }

    @Test
    void dropdownNeedsOptions() {
        assertOptionsRefused(null);
        assertOptionsRefused(List.of());
        assertOptionsRefused(List.of("  "));
        assertOptionsRefused(java.util.Arrays.asList("A", null));
        assertOptionsRefused(IntStream.range(0, 31).mapToObj(i -> "O" + i).toList());
        assertOptionsRefused(List.of("Yes", "YES"));
        assertOptionsRefused(List.of("x".repeat(41)));
        assertThat(service.create(req(VisaFieldScope.COMMON, null, "Type", VisaFieldType.DROPDOWN,
                IntStream.range(0, 30).mapToObj(i -> "O" + i).toList())).getOptions()).hasSize(30);
    }

    private void assertOptionsRefused(List<String> options) {
        assertThatThrownBy(() -> service.create(req(VisaFieldScope.COMMON, null, "Type", VisaFieldType.DROPDOWN, options)))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.getField()).isEqualTo("options"));
    }

    @Test
    void onlyDropdownTakesOptions() {
        assertThatThrownBy(() -> service.create(req(VisaFieldScope.COMMON, null, "Note", VisaFieldType.TEXT, List.of("a"))))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.getMessage()).isEqualTo("Only a Dropdown field takes options"));
        assertThat(service.create(req(VisaFieldScope.COMMON, null, "Note", VisaFieldType.TEXT, List.of())).getOptions()).isEmpty();
    }

    @Test
    void labelIsUniquePerScopeAndCountryIgnoringCase() {
        group.add(field("a", VisaFieldScope.COUNTRY, "Kenya", "Medical", VisaFieldType.TEXT, true, 0));
        assertThatThrownBy(() -> service.create(req(VisaFieldScope.COUNTRY, "kenya", "MEDICAL", VisaFieldType.TEXT, null)))
                .isInstanceOfSatisfying(BookingConflictException.class, e -> assertThat(e.getCode()).isEqualTo("VISA_FIELD_EXISTS"));
    }

    @Test
    void reAddingARemovedLabelRestoresIt() {
        VisaField removed = field("old", VisaFieldScope.COUNTRY, "Kenya", "Medical", VisaFieldType.DROPDOWN, false, 2);
        group.add(field("other", VisaFieldScope.COUNTRY, "Kenya", "Insurance", VisaFieldType.TEXT, false, 1));
        group.add(removed);
        group.add(field("b", VisaFieldScope.COUNTRY, "Kenya", "EVISA", VisaFieldType.TEXT, true, 7));
        VisaFieldResponse back = service.create(req(VisaFieldScope.COUNTRY, "Kenya", "medical", VisaFieldType.DROPDOWN, List.of("RECVD", "HOLD")));
        assertThat(back.getId()).isEqualTo("old");
        assertThat(back.isActive()).isTrue();
        assertThat(back.getLabel()).isEqualTo("medical");
        assertThat(back.getOptions()).containsExactly("RECVD", "HOLD");
        assertThat(back.getSortOrder()).isEqualTo(8);
        verify(auditService).recordUpdate(eq(AuditEntityType.VISA_FIELD), eq("old"), eq("medical"), anyList());

        removed.setActive(false);
        assertThatThrownBy(() -> service.create(req(VisaFieldScope.COUNTRY, "Kenya", "Medical", VisaFieldType.TEXT, null)))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.getMessage()).isEqualTo("A field's type cannot change"));
    }

    @Test
    void atMost40() {
        IntStream.range(0, 40).forEach(i -> group.add(field("f" + i, VisaFieldScope.COMMON, null, "F" + i, VisaFieldType.TEXT, true, i)));
        group.add(field("gone", VisaFieldScope.COMMON, null, "Gone", VisaFieldType.TEXT, false, 99));
        assertThatThrownBy(() -> service.create(req(VisaFieldScope.COMMON, null, "One more", VisaFieldType.TEXT, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("A list can have at most 40 fields");
        assertThatThrownBy(() -> service.create(req(VisaFieldScope.COMMON, null, "Gone", VisaFieldType.TEXT, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void typeNeverChanges() {
        VisaField f = field("a", VisaFieldScope.COMMON, null, "Note", VisaFieldType.TEXT, true, 0);
        given(repository.findById("a")).willReturn(Optional.of(f));
        assertThatThrownBy(() -> service.update("a", req(VisaFieldScope.COMMON, null, "Note", VisaFieldType.DATE, null)))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.getMessage()).isEqualTo("A field's type cannot change"));
    }

    @Test
    void updateRenamesAndMovesNowhere() {
        VisaField f = field("a", VisaFieldScope.COUNTRY, "Kenya", "Note", VisaFieldType.TEXT, true, 0);
        group.add(f);
        group.add(field("b", VisaFieldScope.COUNTRY, "Kenya", "Other", VisaFieldType.TEXT, true, 1));
        group.add(field("c", VisaFieldScope.COUNTRY, "Kenya", "Taken", VisaFieldType.TEXT, false, 2));
        given(repository.findById("a")).willReturn(Optional.of(f));
        given(repository.findById("nope")).willReturn(Optional.empty());

        VisaFieldResponse out = service.update("a", req(VisaFieldScope.COUNTRY, "KENYA", " Remark ", VisaFieldType.TEXT, null));
        assertThat(out.getLabel()).isEqualTo("Remark");
        assertThat(out.isMandatory()).isTrue();
        assertThat(out.getCountry()).isEqualTo("Kenya");
        ArgumentCaptor<List<com.voyra.crm.dto.AuditChange>> changes = ArgumentCaptor.forClass(List.class);
        verify(auditService).recordUpdate(eq(AuditEntityType.VISA_FIELD), eq("a"), eq("Remark"), changes.capture());
        assertThat(changes.getValue()).extracting(com.voyra.crm.dto.AuditChange::getField).containsExactly("label", "mandatory");

        // a removed field's label may be taken; an active one may not; the same label is fine
        assertThat(service.update("a", req(VisaFieldScope.COUNTRY, "Kenya", "Taken", VisaFieldType.TEXT, null)).getLabel()).isEqualTo("Taken");
        assertThatThrownBy(() -> service.update("a", req(VisaFieldScope.COUNTRY, "Kenya", "other", VisaFieldType.TEXT, null)))
                .isInstanceOf(BookingConflictException.class);
        assertThatThrownBy(() -> service.update("a", req(VisaFieldScope.COUNTRY, "Oman", "Taken", VisaFieldType.TEXT, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("A field cannot move to another list");
        assertThatThrownBy(() -> service.update("a", req(VisaFieldScope.COMMON, null, "Taken", VisaFieldType.TEXT, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.update("nope", req(VisaFieldScope.COMMON, null, "x", VisaFieldType.TEXT, null)))
                .isInstanceOf(NotFoundException.class);
        f.setActive(false);
        assertThatThrownBy(() -> service.update("a", req(VisaFieldScope.COUNTRY, "Kenya", "Taken", VisaFieldType.TEXT, null)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void commonFieldUpdateKeepsNoCountry() {
        VisaField f = field("a", VisaFieldScope.COMMON, null, "Note", VisaFieldType.TEXT, true, 0);
        f.setMandatory(true);
        group.add(f);
        given(repository.findById("a")).willReturn(Optional.of(f));
        assertThat(service.update("a", req(VisaFieldScope.COMMON, "ignored", "Note", VisaFieldType.TEXT, null)).getCountry()).isNull();
        verify(auditService).recordUpdate(eq(AuditEntityType.VISA_FIELD), eq("a"), eq("Note"), eq(List.of()));
    }

    @Test
    void renamingAnOptionRewritesValues() {
        VisaField f = field("m", VisaFieldScope.COMMON, null, "Medical", VisaFieldType.DROPDOWN, true, 0);
        f.setOptions(new ArrayList<>(List.of("RECVD", "PENDING", "HOLD")));
        group.add(f);
        given(repository.findById("m")).willReturn(Optional.of(f));
        // RECVD -> RECEIVED at the same position is a rename; PENDING kept; HOLD -> NONE renamed; GIVEN added
        service.update("m", req(VisaFieldScope.COMMON, null, "Medical", VisaFieldType.DROPDOWN, List.of("RECEIVED", "PENDING", "NONE", "GIVEN")));
        verify(visaRepository).renameOption("m", "RECVD", "RECEIVED");
        verify(visaRepository).renameOption("m", "HOLD", "NONE");
        verify(visaRepository, never()).renameOption(eq("m"), eq("PENDING"), anyString());

        // a swap of two existing options is a reorder, not a rename; a removal rewrites nothing
        service.update("m", req(VisaFieldScope.COMMON, null, "Medical", VisaFieldType.DROPDOWN, List.of("PENDING", "RECEIVED")));
        verify(visaRepository, never()).renameOption(eq("m"), eq("RECEIVED"), eq("PENDING"));
        verify(visaRepository, never()).renameOption(eq("m"), eq("PENDING"), eq("RECEIVED"));
        // [PENDING, RECEIVED] -> [RECEIVED, NEW]: RECEIVED moved up and NEW is new; neither is a rename
        service.update("m", req(VisaFieldScope.COMMON, null, "Medical", VisaFieldType.DROPDOWN, List.of("RECEIVED", "NEW")));
        verify(visaRepository, never()).renameOption(eq("m"), anyString(), eq("NEW"));
    }

    @Test
    void deleteIsSoft() {
        VisaField f = field("a", VisaFieldScope.COMMON, null, "Note", VisaFieldType.TEXT, true, 0);
        given(repository.findById("a")).willReturn(Optional.of(f));
        service.delete("a");
        assertThat(f.isActive()).isFalse();
        verify(repository).save(f);
        verify(repository, never()).delete(any());
        verify(auditService).recordDelete(eq(AuditEntityType.VISA_FIELD), eq("a"), eq("Note"), anyList());
        assertThatThrownBy(() -> service.delete("a")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void reorderSetsSortOrderInGivenOrder() {
        VisaField a = field("a", VisaFieldScope.COUNTRY, "Kenya", "A", VisaFieldType.TEXT, true, 0);
        VisaField b = field("b", VisaFieldScope.COUNTRY, "Kenya", "B", VisaFieldType.TEXT, true, 1);
        VisaField c = field("c", VisaFieldScope.COUNTRY, "Kenya", "C", VisaFieldType.TEXT, true, 2);
        group.addAll(List.of(a, b, c, field("gone", VisaFieldScope.COUNTRY, "Kenya", "Gone", VisaFieldType.TEXT, false, 3)));
        List<VisaFieldResponse> out = service.reorder(new VisaFieldOrderRequest(VisaFieldScope.COUNTRY, "Kenya", List.of("b", "a", "c")));
        assertThat(b.getSortOrder()).isZero();
        assertThat(a.getSortOrder()).isEqualTo(1);
        assertThat(out).extracting(VisaFieldResponse::getId).containsExactly("b", "a", "c");
        verify(auditService, never()).recordUpdate(eq(AuditEntityType.VISA_FIELD), eq("c"), anyString(), anyList());
        verify(auditService).recordUpdate(eq(AuditEntityType.VISA_FIELD), eq("b"), eq("B"), anyList());

        assertThatThrownBy(() -> service.reorder(new VisaFieldOrderRequest(VisaFieldScope.COUNTRY, "Kenya", List.of("b", "x", "c"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Give every field of that list once");
        assertThatThrownBy(() -> service.reorder(new VisaFieldOrderRequest(VisaFieldScope.COUNTRY, "Kenya", List.of("b"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.reorder(new VisaFieldOrderRequest(VisaFieldScope.COUNTRY, "Kenya", List.of("a", "a", "b"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.reorder(new VisaFieldOrderRequest(VisaFieldScope.COUNTRY, " ", List.of("a"))))
                .isInstanceOf(FieldValidationException.class);
    }

    @Test
    void forCountryListsCommonThenCountryCaseInsensitive() {
        VisaField common = field("c", VisaFieldScope.COMMON, null, "Passport", VisaFieldType.FILE, true, 0);
        VisaField kenya = field("k", VisaFieldScope.COUNTRY, "Kenya", "EVISA", VisaFieldType.TEXT, true, 0);
        given(repository.findActiveFor("kenya")).willReturn(List.of(common, kenya));
        given(repository.findActiveFor("")).willReturn(List.of(common));
        assertThat(service.forCountry(" kenya ")).extracting(VisaFieldResponse::getId).containsExactly("c", "k");
        assertThat(service.forCountry(null)).extracting(VisaFieldResponse::getId).containsExactly("c");
        assertThat(service.activeFor("kenya")).containsExactly(common, kenya);
    }

    @Test
    void allCountriesAndById() {
        VisaField common = field("c", VisaFieldScope.COMMON, null, "Passport", VisaFieldType.FILE, true, 0);
        given(repository.findByActiveTrueOrderByScopeAscCountryAscSortOrderAsc()).willReturn(List.of(common));
        given(repository.findActiveCountries()).willReturn(List.of("Kenya", "Oman"));
        given(repository.findAllById(List.of("c"))).willReturn(List.of(common));
        assertThat(service.all()).extracting(VisaFieldResponse::getId).containsExactly("c");
        assertThat(service.countries()).containsExactly("Kenya", "Oman");
        assertThat(service.byIds(List.of("c"))).isEqualTo(Map.of("c", common));
        assertThat(service.byIds(Collections.emptyList())).isEmpty();
    }
}
