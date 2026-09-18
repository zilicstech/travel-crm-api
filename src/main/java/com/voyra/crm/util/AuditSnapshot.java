package com.voyra.crm.util;

import com.voyra.crm.dto.AuditChange;
import org.springframework.beans.BeanWrapperImpl;

import java.math.BigDecimal;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns "what changed on this entity" into data instead of forty if-statements per service.
 * Reads named bean properties (Lombok getters) via Spring's {@link BeanWrapperImpl}, stringifies
 * them once with a single formatter so {@code 1200.0} and {@code 1200.00} never read as a
 * change, and diffs two snapshots.
 *
 * <p>The property list is declared per entity as a constant in its service (e.g.
 * {@code BookingService.AUDITED}), which is what makes the audited surface reviewable - read one
 * array to know exactly what history records for that entity.
 */
public final class AuditSnapshot {

    private AuditSnapshot() {
    }

    /** Ordered snapshot of the named properties. Missing/unreadable property is a hard failure. */
    public static Map<String, String> of(Object entity, String... properties) {
        BeanWrapperImpl wrapper = new BeanWrapperImpl(entity);
        Map<String, String> snapshot = new LinkedHashMap<>();
        for (String property : properties) {
            snapshot.put(property, stringify(wrapper.getPropertyValue(property)));
        }
        return snapshot;
    }

    /** Only keys whose stringified values differ; iteration order follows the snapshot order. */
    public static List<AuditChange> diff(Map<String, String> before, Map<String, String> after) {
        List<AuditChange> changes = new ArrayList<>();
        for (Map.Entry<String, String> entry : after.entrySet()) {
            String field = entry.getKey();
            String newValue = entry.getValue();
            String oldValue = before.get(field);
            if (!java.util.Objects.equals(oldValue, newValue)) {
                changes.add(AuditChange.builder().field(field).oldValue(oldValue).newValue(newValue).build());
            }
        }
        return changes;
    }

    /** Snapshot rendered as a change list with a null oldValue - used for CREATE/DELETE rows. */
    public static List<AuditChange> asFullSnapshot(Map<String, String> snapshot) {
        List<AuditChange> changes = new ArrayList<>();
        for (Map.Entry<String, String> entry : snapshot.entrySet()) {
            changes.add(AuditChange.builder().field(entry.getKey()).oldValue(null).newValue(entry.getValue()).build());
        }
        return changes;
    }

    static String stringify(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal bd) {
            return bd.stripTrailingZeros().toPlainString();
        }
        if (value instanceof Enum<?> e) {
            return e.name();
        }
        if (value instanceof Temporal) {
            return value.toString();
        }
        if (value instanceof Collection<?> c) {
            return c.stream().map(AuditSnapshot::stringify).reduce((a, b) -> a + ", " + b).orElse("");
        }
        return String.valueOf(value);
    }
}
