package com.voyra.crm.exception;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Round 2, R2-3: a duplicate never shows the database's index name. */
class DuplicateConstraintMessageParserTest {

    @Test
    void aKnownIndexGetsItsHumanMessage() {
        String chain = "could not execute statement | ERROR: duplicate key value violates unique constraint \"idx_tenant_owner_email\"";
        assertThat(DuplicateConstraintMessageParser.humanMessage(chain)).isEqualTo("This email is already used by another login");
    }

    @Test
    void anUnknownIndexFallsBackToAGenericMessageWithoutItsName() {
        String chain = "ERROR: duplicate key value violates unique constraint \"idx_receipt_number\" | Detail: Key (receipt_number)=(RCP/1) already exists.";
        assertThat(DuplicateConstraintMessageParser.humanMessage(chain))
                .isEqualTo("This record already exists - refresh and try again")
                .doesNotContain("idx_").doesNotContain("receipt_number");
    }
}
