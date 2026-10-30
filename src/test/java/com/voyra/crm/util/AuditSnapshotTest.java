package com.voyra.crm.util;

import com.voyra.crm.dto.AuditChange;
import com.voyra.crm.enums.BookingType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.NotReadablePropertyException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditSnapshotTest {

    public static class Bean {
        public String getName() { return "Alpha"; }
        public BigDecimal getAmount() { return new BigDecimal("1200.00"); }
        public BookingType getType() { return BookingType.HOTEL; }
        public LocalDate getDate() { return LocalDate.of(2026, 1, 2); }
        public List<Object> getTags() { return java.util.Arrays.asList("a", new BigDecimal("2.50"), null); }
        public List<String> getEmpty() { return List.of(); }
        public Object getMissing() { return null; }
    }

    @Test
    void snapshotsNamedPropertiesInOrderWithOneFormatter() {
        Map<String, String> snap = AuditSnapshot.of(new Bean(), "name", "amount", "type", "date", "tags", "empty", "missing");

        assertThat(snap.keySet()).containsExactly("name", "amount", "type", "date", "tags", "empty", "missing");
        assertThat(snap.values()).containsExactly("Alpha", "1200", "HOTEL", "2026-01-02", "a, 2.5, null", "", null);
    }

    @Test
    void anUnknownPropertyIsAHardFailure() {
        assertThatThrownBy(() -> AuditSnapshot.of(new Bean(), "nope")).isInstanceOf(NotReadablePropertyException.class);
    }

    @Test
    void diffListsOnlyChangedKeysInTheAfterOrder() {
        Map<String, String> before = new LinkedHashMap<>();
        before.put("a", "1");
        before.put("b", "2");
        Map<String, String> after = new LinkedHashMap<>();
        after.put("b", "3");
        after.put("a", "1");
        after.put("c", null);
        after.put("d", "new");

        List<AuditChange> changes = AuditSnapshot.diff(before, after);

        assertThat(changes).extracting(AuditChange::getField, AuditChange::getOldValue, AuditChange::getNewValue)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("b", "2", "3"),
                        org.assertj.core.groups.Tuple.tuple("d", null, "new"));
    }

    @Test
    void aFullSnapshotHasNullOldValues() {
        Map<String, String> snap = new LinkedHashMap<>();
        snap.put("x", "1");
        snap.put("y", null);

        assertThat(AuditSnapshot.asFullSnapshot(snap))
                .extracting(AuditChange::getField, AuditChange::getOldValue, AuditChange::getNewValue)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("x", null, "1"),
                        org.assertj.core.groups.Tuple.tuple("y", null, null));
    }
}
