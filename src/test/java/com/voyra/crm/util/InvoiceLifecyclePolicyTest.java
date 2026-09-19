package com.voyra.crm.util;

import com.voyra.crm.enums.InvoiceLifecycle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InvoiceLifecyclePolicyTest {

    @Test
    void onlyDraftIsEditable() {
        assertThatCode(() -> InvoiceLifecyclePolicy.assertEditable(InvoiceLifecycle.DRAFT)).doesNotThrowAnyException();
        assertThatThrownBy(() -> InvoiceLifecyclePolicy.assertEditable(InvoiceLifecycle.ISSUED))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> InvoiceLifecyclePolicy.assertEditable(InvoiceLifecycle.CANCELLED))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void onlyDraftIsDeletable() {
        assertThatCode(() -> InvoiceLifecyclePolicy.assertDeletable(InvoiceLifecycle.DRAFT)).doesNotThrowAnyException();
        assertThatThrownBy(() -> InvoiceLifecyclePolicy.assertDeletable(InvoiceLifecycle.ISSUED))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void onlyDraftIsIssuable() {
        assertThatCode(() -> InvoiceLifecyclePolicy.assertIssuable(InvoiceLifecycle.DRAFT)).doesNotThrowAnyException();
        assertThatThrownBy(() -> InvoiceLifecyclePolicy.assertIssuable(InvoiceLifecycle.ISSUED))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void issuedAndProformaIssuedAreCancellableAtThisStage() {
        assertThatCode(() -> InvoiceLifecyclePolicy.assertCancellable(InvoiceLifecycle.ISSUED)).doesNotThrowAnyException();
        assertThatCode(() -> InvoiceLifecyclePolicy.assertCancellable(InvoiceLifecycle.PROFORMA_ISSUED)).doesNotThrowAnyException();
        assertThatThrownBy(() -> InvoiceLifecyclePolicy.assertCancellable(InvoiceLifecycle.DRAFT))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> InvoiceLifecyclePolicy.assertCancellable(InvoiceLifecycle.CANCELLED))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void onlyDraftIsProformaIssuable() {
        assertThatCode(() -> InvoiceLifecyclePolicy.assertProformaIssuable(InvoiceLifecycle.DRAFT)).doesNotThrowAnyException();
        assertThatThrownBy(() -> InvoiceLifecyclePolicy.assertProformaIssuable(InvoiceLifecycle.ISSUED))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void onlyProformaIssuedIsConvertible() {
        assertThatCode(() -> InvoiceLifecyclePolicy.assertConvertible(InvoiceLifecycle.PROFORMA_ISSUED)).doesNotThrowAnyException();
        assertThatThrownBy(() -> InvoiceLifecyclePolicy.assertConvertible(InvoiceLifecycle.DRAFT))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> InvoiceLifecyclePolicy.assertConvertible(InvoiceLifecycle.ISSUED))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void receivableAllowsIssuedPartiallyPaidAndProformaIssuedOnly() {
        assertThatCode(() -> InvoiceLifecyclePolicy.assertReceivable(InvoiceLifecycle.ISSUED)).doesNotThrowAnyException();
        assertThatCode(() -> InvoiceLifecyclePolicy.assertReceivable(InvoiceLifecycle.PARTIALLY_PAID)).doesNotThrowAnyException();
        assertThatCode(() -> InvoiceLifecyclePolicy.assertReceivable(InvoiceLifecycle.PROFORMA_ISSUED)).doesNotThrowAnyException();
        assertThatThrownBy(() -> InvoiceLifecyclePolicy.assertReceivable(InvoiceLifecycle.DRAFT))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> InvoiceLifecyclePolicy.assertReceivable(InvoiceLifecycle.PAID))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> InvoiceLifecyclePolicy.assertReceivable(InvoiceLifecycle.CANCELLED))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void deriveFromBalancePicksTheCorrectStatus() {
        BigDecimal grandTotal = new BigDecimal("1000.00");
        assertThat(InvoiceLifecyclePolicy.deriveFromBalance(grandTotal, new BigDecimal("1000.00")))
                .isEqualTo(InvoiceLifecycle.ISSUED);
        assertThat(InvoiceLifecyclePolicy.deriveFromBalance(grandTotal, new BigDecimal("400.00")))
                .isEqualTo(InvoiceLifecycle.PARTIALLY_PAID);
        assertThat(InvoiceLifecyclePolicy.deriveFromBalance(grandTotal, BigDecimal.ZERO))
                .isEqualTo(InvoiceLifecycle.PAID);
        assertThat(InvoiceLifecyclePolicy.deriveFromBalance(grandTotal, new BigDecimal("-50.00")))
                .isEqualTo(InvoiceLifecycle.PAID);
    }
}
