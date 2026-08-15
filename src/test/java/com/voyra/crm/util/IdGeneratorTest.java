package com.voyra.crm.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdGeneratorTest {

    @Test
    void generateIdProducesLowercaseUuidString() {
        String id = IdGenerator.generateId();
        assertThat(id).hasSize(36)
                .matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    }

    @Test
    void generateToken32ProducesThirtyTwoCharacters() {
        String token = IdGenerator.generateToken32();
        assertThat(token).hasSize(32).matches("^[0-9A-Z]{32}$");
    }

    @Test
    void zeroLengthThrows() {
        assertThatThrownBy(() -> IdGenerator.generateAlphanumeric(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void lengthAboveThirtyTwoThrows() {
        assertThatThrownBy(() -> IdGenerator.generateAlphanumeric(33))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
