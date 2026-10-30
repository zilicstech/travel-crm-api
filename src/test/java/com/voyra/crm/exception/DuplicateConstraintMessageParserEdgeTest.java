package com.voyra.crm.exception;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DuplicateConstraintMessageParserEdgeTest {

    @Test
    void noMessageIsNotADuplicateAndGetsTheGenericText() {
        assertThat(DuplicateConstraintMessageParser.isDuplicateUniqueViolation(null)).isFalse();
        assertThat(DuplicateConstraintMessageParser.humanMessage(null)).isEqualTo(DuplicateConstraintMessageParser.GENERIC);
        assertThat(DuplicateConstraintMessageParser.humanMessage("duplicate key, no constraint named")).isEqualTo(DuplicateConstraintMessageParser.GENERIC);
    }

    @Test
    void theChainSkipsEmptyMessagesAndStopsAfterFiveLevels() {
        Throwable chain = new RuntimeException("m6");
        for (int i = 5; i >= 1; i--) {
            chain = new RuntimeException(i == 2 ? null : "m" + i, chain);
        }
        assertThat(DuplicateConstraintMessageParser.collectMessageChain(chain)).isEqualTo("m1 | m3 | m4 | m5");
    }
}
