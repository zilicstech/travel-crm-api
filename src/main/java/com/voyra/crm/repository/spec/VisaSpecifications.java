package com.voyra.crm.repository.spec;

import com.voyra.crm.entity.Visa;
import com.voyra.crm.entity.VisaField;
import com.voyra.crm.entity.VisaFile;
import com.voyra.crm.enums.VisaFieldType;
import com.voyra.crm.exception.FieldValidationException;
import com.voyra.crm.models.VisaListFilter;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The visa sheet's search, filters and field sort, in SQL so a page is a real page. A field id is looked up in the
 * active fields first and only ever bound as a parameter (the jsonb key), never written into the query text.
 */
public final class VisaSpecifications {

    private VisaSpecifications() {
    }

    /**
     * @param fields   the active fields filters may name (common plus the filtered country's), by id
     * @param agentId  the agent the caller is limited to, or null for the owner
     */
    public static Specification<Visa> matching(VisaListFilter filter, Map<String, VisaField> fields, String agentId) {
        List<FieldFilter> fieldFilters = parse(filter.fieldFilters(), fields);
        return (root, query, cb) -> {
            List<Predicate> and = new ArrayList<>();
            if (agentId != null) and.add(cb.equal(root.get("agentId"), agentId));
            if (filter.agentId() != null && !filter.agentId().isBlank()) and.add(cb.equal(root.get("agentId"), filter.agentId()));
            if (filter.country() != null && !filter.country().isBlank()) {
                and.add(cb.equal(cb.lower(root.get("country")), filter.country().trim().toLowerCase(Locale.ROOT)));
            }
            if (filter.status() != null) and.add(cb.equal(root.get("status"), filter.status()));
            if (filter.openedFrom() != null) and.add(cb.greaterThanOrEqualTo(root.get("createdDate"), filter.openedFrom().atStartOfDay()));
            if (filter.openedTo() != null) and.add(cb.lessThan(root.get("createdDate"), filter.openedTo().plusDays(1).atStartOfDay()));
            if (filter.q() != null && !filter.q().isBlank()) and.add(search(root, cb, filter.q().trim()));
            for (FieldFilter f : fieldFilters) and.add(f.predicate(root, query, cb));
            return cb.and(and.toArray(Predicate[]::new));
        };
    }

    /** Orders by one configured field's value, empty values last either way, then newest case first. */
    public static Specification<Visa> sortedByField(VisaField field, boolean ascending) {
        return (root, query, cb) -> {
            if (!Long.class.equals(query.getResultType()) && !long.class.equals(query.getResultType())) {
                Expression<String> value = fieldValue(root, cb, field.getId());
                query.orderBy(cb.asc(cb.selectCase().when(cb.isNull(value), 1).otherwise(0)),
                        ascending ? cb.asc(value) : cb.desc(value), cb.desc(root.get("caseNo")));
            }
            return null;
        };
    }

    private static Predicate search(Root<Visa> root, CriteriaBuilder cb, String q) {
        String like = "%" + q.toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        List<Predicate> or = new ArrayList<>(List.of(
                cb.like(cb.lower(root.get("memberName")), like, '\\'),
                cb.like(cb.lower(root.get("clientName")), like, '\\'),
                cb.like(cb.lower(root.get("passportNumber")), like, '\\')));
        String digits = q.startsWith("#") ? q.substring(1) : q;
        if (digits.matches("\\d{1,18}")) {
            or.add(cb.equal(root.get("caseNo"), Long.parseLong(digits)));
        }
        return cb.or(or.toArray(Predicate[]::new));
    }

    private static Expression<String> fieldValue(Root<Visa> root, CriteriaBuilder cb, String fieldId) {
        return cb.function("jsonb_extract_path_text", String.class, root.get("fieldValues"), cb.literal(fieldId));
    }

    /** One {@code f.<id>[.from|.to]} parameter, checked. */
    private record FieldFilter(VisaField field, String part, String value) {

        Predicate predicate(Root<Visa> root, CriteriaQuery<?> query, CriteriaBuilder cb) {
            Expression<String> v = fieldValue(root, cb, field.getId());
            return switch (field.getType()) {
                case TEXT -> cb.like(cb.lower(v), "%" + value.toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%", '\\');
                case DROPDOWN, YES_NO -> cb.equal(v, value);
                case DATE -> part.equals("from") ? cb.greaterThanOrEqualTo(v, value) : cb.lessThanOrEqualTo(v, value);
                case FILE -> {
                    Subquery<String> file = query.subquery(String.class);
                    Root<VisaFile> f = file.from(VisaFile.class);
                    file.select(f.get("id")).where(cb.equal(f.get("visaId"), root.get("id")), cb.equal(f.get("fieldId"), field.getId()));
                    yield value.equals("has") ? cb.exists(file) : cb.not(cb.exists(file));
                }
            };
        }
    }

    private static List<FieldFilter> parse(Map<String, String> raw, Map<String, VisaField> fields) {
        List<FieldFilter> out = new ArrayList<>();
        raw.forEach((key, rawValue) -> {
            String value = rawValue.trim(); // VisaListFilter holds no null value
            if (value.isEmpty()) {
                return;
            }
            String id = key;
            String part = "";
            if (key.endsWith(".from") || key.endsWith(".to")) {
                part = key.substring(key.lastIndexOf('.') + 1);
                id = key.substring(0, key.lastIndexOf('.'));
            }
            VisaField field = fields.get(id);
            String param = "f." + key;
            if (field == null) {
                throw new FieldValidationException(param, "That field is not on this sheet");
            }
            boolean ranged = field.getType() == VisaFieldType.DATE;
            if (ranged == part.isEmpty()) {
                throw new FieldValidationException(param, ranged ? "Filter a date field with .from or .to" : "Only a date field takes .from or .to");
            }
            out.add(new FieldFilter(field, part, canonical(field, value, param)));
        });
        return out;
    }

    private static String canonical(VisaField field, String value, String param) {
        return switch (field.getType()) {
            case TEXT -> value;
            case DROPDOWN -> field.getOptions().stream().filter(o -> o.equalsIgnoreCase(value)).findFirst()
                    .orElseThrow(() -> new FieldValidationException(param, "Pick one of: " + String.join(", ", field.getOptions())));
            case YES_NO -> {
                if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
                    throw new FieldValidationException(param, "Pick Yes or No");
                }
                yield value.toLowerCase(Locale.ROOT);
            }
            case DATE -> {
                try {
                    yield LocalDate.parse(value).toString();
                } catch (DateTimeParseException e) {
                    throw new FieldValidationException(param, "Enter a date as YYYY-MM-DD");
                }
            }
            case FILE -> {
                if (!value.equals("has") && !value.equals("missing")) {
                    throw new FieldValidationException(param, "Filter a file field by has or missing");
                }
                yield value;
            }
        };
    }
}
