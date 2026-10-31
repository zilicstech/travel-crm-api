package com.voyra.crm.util;

import com.voyra.crm.entity.Invoice;
import com.voyra.crm.entity.InvoiceLineItem;
import com.voyra.crm.entity.Tenant;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A renderer that fails surfaces its own error, not the "no pages" error of closing an empty document. */
class PdfRendererErrorsTest {

    @Test
    void aFailureBeforeAnythingIsPrintedSurfacesItself() {
        assertThatThrownBy(() -> InvoicePdfRenderer.write(null, List.of()))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("invoice");
        assertThatThrownBy(() -> ReceiptVoucherRenderer.write(null, null, null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("receipt");
        assertThatThrownBy(() -> AirInvoicePdfRenderer.write(null, List.of(), List.of(), null, Map.of(), null, null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("invoice");
    }

    @Test
    void aFailureAfterTheTitleSurfacesItself() {
        assertThatThrownBy(() -> PaymentAdvicePdfRenderer.write(null, null, null, null, null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("payment");
        assertThatThrownBy(() -> PdfTableWriter.write("Report", null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("table");
    }

    @Test
    void anAgencyWithNoNameAndNoLegalNameOnTheInvoicePrintsAnEmptyName() {
        Invoice invoice = Invoice.builder().currencyCode("INR").taxableValue(new BigDecimal("100.00"))
                .grandTotal(new BigDecimal("100.00")).grandTotalInr(new BigDecimal("100.00")).build();
        InvoiceLineItem line = InvoiceLineItem.builder().description("Line Desc").taxableValue(new BigDecimal("100.00")).build();

        String text = PdfText.flat(AirInvoicePdfRenderer.write(invoice, List.of(line), List.of(), new Tenant(), new HashMap<>(), null, null));

        assertThat(text).contains(PdfText.flat("Tax Invoice", "Line Desc", "E. & O. E.", "for"));
        assertThat(text).doesNotContain("null").doesNotContain("for-");
    }
}
