package com.voyra.crm.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** BUG-MO-08: a payment reference longer than its 100-character column is refused with a message, never cut. */
class PaymentReferenceLengthTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void referencesOverOneHundredCharactersAreRefused() {
        String tooLong = "R".repeat(101);
        BookingPaymentRequest booking = new BookingPaymentRequest();
        booking.setReference(tooLong);
        PaymentReceiptRequest receipt = new PaymentReceiptRequest();
        receipt.setInstrumentRef(tooLong);
        SupplierPaymentRequest supplier = new SupplierPaymentRequest();
        supplier.setInstrumentRef(tooLong);

        for (Object request : new Object[] { booking, receipt, supplier }) {
            assertThat(validator.validate(request)).extracting(v -> v.getMessage())
                    .as(request.getClass().getSimpleName()).contains("Reference must be 100 characters or fewer");
        }
        booking.setReference("R".repeat(100));
        assertThat(validator.validate(booking)).extracting(v -> v.getMessage()).doesNotContain("Reference must be 100 characters or fewer");
    }
}
