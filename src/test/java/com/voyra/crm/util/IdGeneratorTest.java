package com.voyra.crm.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdGeneratorTest {

    @Test
    void generate6ProducesSixUppercaseBase36Characters() {
        String id = IdGenerator.generate6();
        assertThat(id).hasSize(6).matches("^[0-9A-Z]{6}$");
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
