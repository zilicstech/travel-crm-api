package com.voyra.crm.repository.spec;

import com.voyra.crm.entity.Visa;
import com.voyra.crm.entity.VisaField;
import com.voyra.crm.enums.VisaFieldScope;
import com.voyra.crm.enums.VisaFieldType;
import com.voyra.crm.enums.VisaStatus;
import com.voyra.crm.exception.FieldValidationException;
import com.voyra.crm.models.VisaListFilter;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

class VisaSpecificationsTest {

    private final CriteriaRecorder rec = new CriteriaRecorder();

    private static VisaField f(String id, VisaFieldType type, String... options) {
        return VisaField.builder().id(id).scope(VisaFieldScope.COMMON).label(id).type(type).options(List.of(options)).active(true).build();
    }

    private final Map<String, VisaField> fields = Map.of("med", f("med", VisaFieldType.DROPDOWN, "RECVD", "PENDING"),
            "ok", f("ok", VisaFieldType.YES_NO), "note", f("note", VisaFieldType.TEXT), "exp", f("exp", VisaFieldType.DATE),
            "con", f("con", VisaFieldType.FILE));

    private String build(Specification<Visa> spec) {
        return rec.render(spec.toPredicate(rec.root(), rec.query, rec.cb));
    }

    private static VisaListFilter filter(String q, Map<String, String> fieldFilters) {
        return new VisaListFilter(q, null, null, null, null, null, fieldFilters);
    }

    private static Map<String, String> map(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    @Test
    void nothingAskedMatchesEverything() {
        assertThat(build(VisaSpecifications.matching(filter(null, null), fields, null))).isEqualTo("and()");
        assertThat(build(VisaSpecifications.matching(new VisaListFilter(" ", " ", null, " ", null, null, map("med", " ")), fields, null)))
                .isEqualTo("and()");
    }

    @Test
    void anAgentIsAlwaysScopedAndBuiltInFiltersApply() {
        VisaListFilter all = new VisaListFilter(null, " Kenya ", VisaStatus.SUBMITTED, "A2", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30), null);
        assertThat(build(VisaSpecifications.matching(all, fields, "A1"))).isEqualTo("and(equal(root.agentId, A1), equal(root.agentId, A2), "
                + "equal(lower(root.country), kenya), equal(root.status, SUBMITTED), greaterThanOrEqualTo(root.createdDate, 2026-11-01T00:00), "
                + "lessThan(root.createdDate, 2026-12-01T00:00))");
    }

    @Test
    void searchMatchesTravellerClientPassportAndCaseNumber() {
        assertThat(build(VisaSpecifications.matching(filter(" #42 ", null), fields, null))).isEqualTo("and(or("
                + "like(lower(root.memberName), %#42%, \\), like(lower(root.clientName), %#42%, \\), like(lower(root.passportNumber), %#42%, \\), "
                + "equal(root.caseNo, 42)))");
        assertThat(build(VisaSpecifications.matching(filter("Ra_vi%", null), fields, null)))
                .contains("like(lower(root.memberName), %ra\\_vi\\%%, \\)").doesNotContain("caseNo");
    }

    @Test
    void fieldFiltersAreBoundByTypeNeverWrittenIntoSql() {
        String sql = build(VisaSpecifications.matching(filter(null, map("med", "pending", "ok", "TRUE", "note", "Ex_p", "exp.from", "2026-01-01",
                "exp.to", "2026-12-31", "con", "has")), fields, null));
        String med = "function(jsonb_extract_path_text, String, root.fieldValues, literal(med))";
        assertThat(sql).contains("equal(" + med + ", PENDING)")
                .contains("equal(function(jsonb_extract_path_text, String, root.fieldValues, literal(ok)), true)")
                .contains("like(lower(function(jsonb_extract_path_text, String, root.fieldValues, literal(note))), %ex\\_p%, \\)")
                .contains("greaterThanOrEqualTo(function(jsonb_extract_path_text, String, root.fieldValues, literal(exp)), 2026-01-01)")
                .contains("lessThanOrEqualTo(function(jsonb_extract_path_text, String, root.fieldValues, literal(exp)), 2026-12-31)")
                .contains("exists(query.subquery(String))");
        assertThat(rec.calls()).contains("VisaFile", "equal(VisaFile.visaId, root.id)", "equal(VisaFile.fieldId, con)");
        assertThat(build(VisaSpecifications.matching(filter(null, map("con", "missing", "ok", "False")), fields, null)))
                .contains("not(exists(query.subquery(String)))").contains("literal(ok)), false)");
    }

    @Test
    void anUnknownRemovedOrMisusedFieldIsRefused() {
        assertRefused(map("gone", "x"), "f.gone", "That field is not on this sheet");
        assertRefused(map("med.from", "x"), "f.med.from", "Only a date field takes .from or .to");
        assertRefused(map("exp", "2026-01-01"), "f.exp", "Filter a date field with .from or .to");
        assertRefused(map("med", "HOLD"), "f.med", "Pick one of: RECVD, PENDING");
        assertRefused(map("ok", "maybe"), "f.ok", "Pick Yes or No");
        assertRefused(map("exp.to", "31/12/2026"), "f.exp.to", "Enter a date as YYYY-MM-DD");
        assertRefused(map("con", "yes"), "f.con", "Filter a file field by has or missing");
        assertRefused(map("x'); drop table visa; --", "1"), "f.x'); drop table visa; --", "That field is not on this sheet");
    }

    private void assertRefused(Map<String, String> filters, String param, String message) {
        assertThatThrownBy(() -> VisaSpecifications.matching(filter(null, filters), fields, null))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> {
                    assertThat(e.getField()).isEqualTo(param);
                    assertThat(e.getMessage()).isEqualTo(message);
                });
    }

    @Test
    void aFieldSortPutsEmptyValuesLastThenNewestFirstButNotOnTheCount() {
        assertThat(VisaSpecifications.sortedByField(fields.get("med"), true).toPredicate(rec.root(), rec.query, rec.cb)).isNull();
        String med = "function(jsonb_extract_path_text, String, root.fieldValues, literal(med))";
        assertThat(rec.calls()).anyMatch(c -> c.startsWith("query.orderBy(asc(selectCase().when(isNull(" + med + "), 1).otherwise(0)), asc(" + med
                + "), desc(root.caseNo))"));
        VisaSpecifications.sortedByField(fields.get("med"), false).toPredicate(rec.root(), rec.query, rec.cb);
        assertThat(rec.calls()).anyMatch(c -> c.contains("desc(" + med + ")"));

        int before = rec.calls().size();
        when(rec.query.getResultType()).thenReturn((Class) Long.class);
        VisaSpecifications.sortedByField(fields.get("med"), true).toPredicate(rec.root(), rec.query, rec.cb);
        when(rec.query.getResultType()).thenReturn((Class) long.class);
        VisaSpecifications.sortedByField(fields.get("med"), true).toPredicate(rec.root(), rec.query, rec.cb);
        assertThat(rec.calls().subList(before, rec.calls().size())).noneMatch(c -> c.startsWith("query.orderBy"));
    }
}
