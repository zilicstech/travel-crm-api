package com.voyra.crm.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GstStateCodeTest {

    @Test
    void listsEveryStatutoryCodeInOrderAndNamesThem() {
        assertThat(GstStateCode.all()).hasSize(36).containsEntry("27", "Maharashtra");
        assertThat(GstStateCode.all().keySet().iterator().next()).isEqualTo("01");
        assertThat(GstStateCode.nameOf("23")).isEqualTo("Madhya Pradesh");
        assertThat(GstStateCode.nameOf("25")).isNull();
    }

    @Test
    void onlyAKnownCodeIsValid() {
        assertThat(GstStateCode.isValid("29")).isTrue();
        assertThat(GstStateCode.isValid("25")).isFalse();
        assertThat(GstStateCode.isValid(null)).isFalse();
    }
}
