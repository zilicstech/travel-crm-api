package com.voyra.crm.util;

import com.voyra.crm.entity.VisaField;
import com.voyra.crm.enums.VisaFieldScope;
import com.voyra.crm.enums.VisaFieldType;
import com.voyra.crm.exception.FieldValidationException;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

class VisaFieldValuesTest {

    private static VisaField f(String id, VisaFieldType type, boolean mandatory, String... options) {
        return VisaField.builder().id(id).scope(VisaFieldScope.COMMON).label(id.toUpperCase()).type(type)
                .options(List.of(options)).mandatory(mandatory).active(true).build();
    }

    private final VisaField text = f("t", VisaFieldType.TEXT, false);
    private final VisaField drop = f("d", VisaFieldType.DROPDOWN, false, "RECVD", "PENDING");
    private final VisaField date = f("dt", VisaFieldType.DATE, false);
    private final VisaField yes = f("y", VisaFieldType.YES_NO, true);
    private final VisaField file = f("fl", VisaFieldType.FILE, true);
    private final Map<String, VisaField> asked = Map.of("t", text, "d", drop, "dt", date, "y", yes, "fl", file);

    private static Map<String, String> errors(Runnable call) {
        try {
            call.run();
        } catch (FieldValidationException e) {
            return e.getErrors();
        }
        throw new AssertionError("no error");
    }

    private static Map<String, String> map(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    @Test
    void cleansGoodValuesPerType() {
        Map<String, String> out = VisaFieldValues.check(map("t", "  hello ", "d", "pending", "dt", "2026-12-01", "y", "TRUE"),
                asked, Set.of("fl"), List.of(yes, file));
        assertThat(out).containsOnly(entry("t", "hello"), entry("d", "PENDING"), entry("dt", "2026-12-01"), entry("y", "true"));
    }

    @Test
    void refusesBadValuesAllAtOnce() {
        Map<String, String> errors = errors(() -> VisaFieldValues.check(
                map("t", "x".repeat(501), "d", "HOLD", "dt", "01/12/2026", "y", "maybe", "zz", "x", "fl", "contract.pdf"),
                asked, Set.of("t", "nope"), List.of()));
        assertThat(errors).containsEntry("values.t", "This field takes no file")
                .containsEntry("values.d", "Pick one of: RECVD, PENDING")
                .containsEntry("values.dt", "Enter a date as YYYY-MM-DD")
                .containsEntry("values.y", "Pick Yes or No")
                .containsEntry("values.zz", "This field is not asked for this country")
                .containsEntry("values.fl", "Upload the file instead")
                .containsEntry("values.nope", "This field takes no file");
        assertThat(errors(() -> VisaFieldValues.check(map("t", "x".repeat(501)), asked, Set.of(), List.of())))
                .containsEntry("values.t", "Write at most 500 characters");
    }

    @Test
    void aFileFieldTakesNoValueEvenAnEmptyOneAndABadMandatoryValueKeepsItsOwnError() {
        assertThat(errors(() -> VisaFieldValues.check(map("fl", ""), asked, Set.of("fl"), List.of())))
                .containsOnly(entry("values.fl", "Upload the file instead"));
        assertThat(errors(() -> VisaFieldValues.check(map("y", "maybe"), asked, Set.of("fl"), List.of(yes))))
                .containsOnly(entry("values.y", "Pick Yes or No"));
    }

    @Test
    void textOf500IsFine() {
        assertThat(VisaFieldValues.check(map("t", "x".repeat(500)), asked, Set.of(), List.of())).containsKey("t");
    }

    @Test
    void mandatoryMissingOnCreateIncludingAFileWithoutAPart() {
        assertThat(errors(() -> VisaFieldValues.check(map("t", "a"), asked, Set.of(), List.of(yes, file))))
                .containsOnly(entry("values.y", "This field is required"), entry("values.fl", "Upload this file"));
    }

    @Test
    void aMandatoryYesNoNeedsAnExplicitChoiceAndCannotBeCleared() {
        assertThat(errors(() -> VisaFieldValues.check(map("y", " "), asked, Set.of("fl"), List.of(yes))))
                .containsOnly(entry("values.y", "This field is required"));
        assertThat(VisaFieldValues.check(map("y", "false"), asked, Set.of(), List.of(yes))).containsEntry("y", "false");
    }

    @Test
    void aClearedOptionalFieldComesBackEmpty() {
        Map<String, String> sent = map("t", "", "d", null);
        assertThat(VisaFieldValues.check(sent, asked, Set.of(), List.of())).containsOnly(entry("t", ""), entry("d", ""));
    }

    @Test
    void anInactiveFieldIsNotAsked() {
        // the caller passes only active fields; a removed one is simply absent
        assertThat(errors(() -> VisaFieldValues.check(map("old", "x"), Map.of(), Set.of(), List.of())))
                .containsEntry("values.old", "This field is not asked for this country");
        assertThat(VisaFieldValues.key("f1")).isEqualTo("values.f1");
    }
}
