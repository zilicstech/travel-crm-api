package com.voyra.crm.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** BUG-CRX-03: a long cancel reason is refused with a message, not by the varchar(255) column (it is also quoted in narrations). */
class CancelReasonLengthTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void aCancelReasonOver150CharactersIsRefusedAndOneAtTheLimitIsAccepted() {
        InvoiceCancelRequest invoice = new InvoiceCancelRequest();
        invoice.setReason("x".repeat(288));
        SupplierInvoiceCancelRequest bill = new SupplierInvoiceCancelRequest();
        bill.setReason("x".repeat(300));

        assertThat(validator.validate(invoice)).extracting(v -> v.getMessage()).containsExactly("Keep the reason to 150 characters or fewer");
        assertThat(validator.validate(bill)).hasSize(1);

        invoice.setReason("x".repeat(150));
        assertThat(validator.validate(invoice)).isEmpty();
    }
}
