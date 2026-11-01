package com.voyra.crm.util;

import com.voyra.crm.util.SupplierIds.Scoped;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SupplierIdsTest {

    @Test
    void parsesAtTheFirstColonOnly() {
        assertThat(SupplierIds.parse("s1:opt:9:x")).contains(new Scoped("s1", "opt:9:x"));
    }

    @Test
    void noNamespaceOrABlankSideIsEmpty() {
        assertThat(SupplierIds.parse(null)).isEmpty();
        assertThat(SupplierIds.parse("abc")).isEmpty();
        assertThat(SupplierIds.parse(":x")).isEmpty();
        assertThat(SupplierIds.parse("s1:")).isEmpty();
    }

    @Test
    void wrapPrefixesTheSupplierAndKeepsNullNull() {
        assertThat(SupplierIds.wrap("s1", "o1")).isEqualTo("s1:o1");
        assertThat(SupplierIds.wrap("s1", null)).isNull();
    }
}
